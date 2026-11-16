#!/usr/bin/env python3
"""Run the current PBW and packaged PKJS with SDK transport and a synthetic bridge."""

import argparse
import bz2
import json
import os
from pathlib import Path
import queue
import re
import signal
import shutil
import socket
import subprocess
import tempfile
import threading
import time
from uuid import UUID
import zipfile

from libpebble2.communication import PebbleConnection
from libpebble2.communication.transports.qemu import QemuTransport
from libpebble2.protocol.apps import AppRunState, AppRunStateStart
from libpebble2.services.appmessage import AppMessageService, CString, Int32, Uint32
from libpebble2.services.install import AppInstaller
from libpebble2.services.screenshot import Screenshot

ROOT = Path(__file__).resolve().parents[2]


def capture_watch(connection, path):
    result = queue.Queue()

    def capture():
        try:
            import png
            image = Screenshot(connection).grab_image()
            png.from_array(image, mode="RGB;8").save(str(path))
            result.put(None)
        except Exception as error:
            result.put(error)

    threading.Thread(target=capture, daemon=True).start()
    try:
        error = result.get(timeout=5)
    except queue.Empty as error:
        raise TimeoutError("watch screenshot exceeded five seconds") from error
    if error:
        raise error


def unused_port():
    with socket.socket() as connection:
        connection.bind(("127.0.0.1", 0))
        return connection.getsockname()[1]


def wait_port(port, process, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("QEMU exited before readiness")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                return
        except OSError:
            time.sleep(0.1)
    raise TimeoutError("QEMU protocol port did not become ready")


class Smoke:
    def __init__(self, connection, app, version, release, peer, trace=None):
        self.connection = connection
        self.app = app
        self.version = version
        self.release = release
        self.peer = peer
        self.trace = trace or (lambda direction, fields: None)
        self.messages = queue.Queue()
        self.phone = queue.Queue()
        self.acks = queue.Queue()
        self.applied = []
        self.profile_serial = 0
        self.requests = 0
        self.pkjs_ready = threading.Event()
        self.context_sent = False
        self.service = AppMessageService(connection)
        self.service.register_handler(
            "appmessage",
            lambda transaction, app, data: self.messages.put(data) if app == self.app else None,
        )
        self.service.register_handler("ack", lambda transaction, app: self.acks.put((transaction, True)))
        self.service.register_handler("nack", lambda transaction, app: self.acks.put((transaction, False)))

    def peer_send(self, value):
        self.peer.stdin.write(json.dumps(value) + "\n")
        self.peer.stdin.flush()

    def send(self, kind, values):
        return self.send_frame({
            0: Int32(self.version), 1: Int32(kind), 35: CString(self.release), **values,
        })

    def send_frame(self, values):
        self.trace("phone-to-watch", {key: value.value for key, value in values.items()})
        transaction = self.service.send_message(self.app, values)
        received, accepted = self.acks.get(timeout=10)
        if received != transaction or not accepted:
            raise RuntimeError(f"watch did not acknowledge frame: {transaction}, {received}, {accepted}")
    def snapshot(self):
        # Independent representative wire fixture; parity tests check the contract.
        fields = {key: Int32(0) for key in [
            5, *range(11, 30), 37, *range(40, 51), 54,
        ]}
        fields.update({
            5: Int32(1), 6: Uint32(int(time.time())), 10: Uint32(60),
            12: Int32(50), 13: Int32(40), 19: Int32(60),
            42: Int32(10), 43: Int32(10), 44: Int32(10),
            45: Int32(18), 46: Int32(20), 47: Int32(22), 50: Int32(24),
            55: Uint32(1000), 56: Uint32(0),
        })
        self.send(1, fields)


    def receive(self, timeout=0.1):
        if self.peer.poll() is not None:
            raise RuntimeError("packaged JavaScript peer exited; inspect phone.log")
        try:
            event = self.phone.get_nowait()
        except queue.Empty:
            event = None
        if event:
            if event["event"] == "ready":
                self.pkjs_ready.set()
            elif event["event"] == "send":
                values = {}
                for key, value in event["frame"].items():
                    key = int(key)
                    values[key] = CString(value) if isinstance(value, str) else (
                        Uint32(value) if key in (52, 53) else Int32(value)
                    )
                self.send_frame(values)
                self.peer_send({"op": "sent", "id": event["id"], "success": True})
            elif event["event"] == "settings":
                self.peer_send({"op": "save", "url": event["url"]})
        try:
            message = self.messages.get(timeout=timeout)
        except queue.Empty:
            return
        self.trace("watch-to-phone", message)
        if message.get(0) != self.version or message.get(35) != self.release:
            raise RuntimeError("watch protocol/release mismatch")
        self.peer_send({"op": "message", "payload": message})
        kind = message.get(1)
        if kind == 4:
            self.requests += 1
            self.snapshot()
        elif kind == 7:
            self.profile_serial += 1
            self.send(6, {
                33: Int32(self.profile_serial), 30: Int32(0), 31: Int32(1),
                32: CString("10|Hiking"), 4: Int32(0), 39: Int32(1),
            })
        elif kind == 9:
            if message.get(4) != 0:
                raise RuntimeError(f"watch rejected settings: {message}")
            self.applied.append(message[33])

    def until(self, predicate, label, timeout=30):
        deadline = time.monotonic() + timeout
        while not predicate():
            if time.monotonic() >= deadline:
                raise TimeoutError(label)
            if self.pkjs_ready.is_set() and not self.context_sent:
                self.send(10, {5: Int32(0)})
                self.send(10, {5: Int32(1), 51: CString("10"), 9: CString("Hiking")})
                self.context_sent = True
            self.receive()

    def run(self, artifacts):
        self.connection.send_packet(AppRunState(command=1, data=AppRunStateStart(uuid=self.app)))
        self.until(lambda: self.requests > 0, "watch launch/snapshot exchange")
        self.peer_send({"op": "ready"})
        self.until(lambda: self.applied, "initial watch configuration")
        before = len(self.applied)
        self.peer_send({"op": "configure"})
        self.until(lambda: len(self.applied) > before, "packaged settings open/save/apply")
        capture_watch(self.connection, artifacts / "configured-watch.png")
        print("PASS: launch, snapshot exchange, packaged settings open/save, watch CONFIG_RESULT=APPLIED")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("platform", choices=["emery", "gabbro"])
    args = parser.parse_args()

    def stop(signum, frame):
        raise TimeoutError(f"watch smoke interrupted: signal {signum}")

    signal.signal(signal.SIGALRM, stop)
    signal.signal(signal.SIGTERM, stop)
    signal.alarm(120)
    artifacts = ROOT / "build/watch-smoke" / args.platform
    if artifacts.exists():
        shutil.rmtree(artifacts)
    artifacts.mkdir(parents=True, exist_ok=True)
    package = json.loads((ROOT / "watchapp/package.json").read_text())
    pbw = ROOT / "watchapp/build/watchapp.pbw"
    with zipfile.ZipFile(pbw) as bundle:
        js = bundle.read("pebble-js-app.js").decode()
    (artifacts / "packaged-pkjs.js").write_text(js)
    version = int(re.search(r"var V = (\d+);", js)[1])
    versions = dict(line.split("=", 1) for line in (ROOT / "tools/podman/versions.env").read_text().splitlines() if "=" in line)
    sdk = Path(os.environ.get("XDG_DATA_HOME", "/opt/pebble-data")) / "pebble-sdk/SDKs" / versions["PEBBLE_SDK_VERSION"]
    board = sdk / "sdk-core/pebble" / args.platform
    processes = []
    connection = None
    closing = threading.Event()
    reader_errors = queue.Queue()
    with tempfile.TemporaryDirectory(prefix="trackglance-smoke-") as temporary:
        flash = Path(temporary) / "flash.bin"
        flash.write_bytes(bz2.decompress((board / "qemu/qemu_spi_flash.bin.bz2").read_bytes()))
        port = unused_port()
        with (artifacts / "qemu.log").open("w") as qemu_log, \
                (artifacts / "phone.log").open("w") as phone_log, \
                (artifacts / "frames.jsonl").open("w") as frames:
            try:
                qemu = subprocess.Popen([
                    str(sdk / "toolchain/bin/qemu-pebble"), "-rtc", "base=utc", "-serial", "null",
                    "-serial", f"tcp:127.0.0.1:{port},server=on,wait=off",
                    "-serial", f"file:{artifacts / 'watch.log'}",
                    "-kernel", str(board / "qemu/qemu_micro_flash.bin"),
                    "-machine", f"pebble-{args.platform}", "-cpu", "cortex-m33",
                    "-drive", f"if=mtd,format=raw,file={flash}", "-audio", "driver=none", "-display", "none",
                ], stdout=qemu_log, stderr=subprocess.STDOUT)
                processes.append(qemu)
                wait_port(port, qemu)
                connection = PebbleConnection(QemuTransport("127.0.0.1", port))
                connection.connect()

                def read_connection():
                    try:
                        connection.run_sync()
                    except Exception as error:
                        if not closing.is_set():
                            reader_errors.put(error)

                threading.Thread(target=read_connection, daemon=True).start()
                connection.fetch_watch_info()
                peer = subprocess.Popen([
                    "node", "watchapp/test/phone_peer.js",
                    str(artifacts / "packaged-pkjs.js"), args.platform,
                ], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=phone_log, text=True, cwd=ROOT)
                processes.append(peer)
                def trace(direction, fields):
                    frames.write(json.dumps({"time": time.monotonic(), "direction": direction, "fields": fields}) + "\n")
                    frames.flush()

                smoke = Smoke(connection, UUID(package["pebble"]["uuid"]), version, package["version"], peer, trace)

                def read_phone():
                    try:
                        for line in peer.stdout:
                            smoke.phone.put(json.loads(line))
                    except Exception as error:
                        reader_errors.put(error)

                threading.Thread(target=read_phone, daemon=True).start()
                AppInstaller(connection, str(pbw)).install()
                smoke.run(artifacts)
                if not reader_errors.empty():
                    raise RuntimeError("SDK/JavaScript reader failed") from reader_errors.get()
            except Exception as error:
                (artifacts / "failure.txt").write_text(str(error))
                if connection is not None and connection.connected and reader_errors.empty():
                    try:
                        capture_watch(connection, artifacts / "failure-watch.png")
                    except Exception as capture_error:
                        (artifacts / "screenshot-failure.txt").write_text(str(capture_error))
                raise
            finally:
                closing.set()
                for process in reversed(processes):
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()


if __name__ == "__main__":
    main()
