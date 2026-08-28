#!/usr/bin/env python3
"""Transparent Core/PebbleOS QEMU relay with a private JSON control socket."""

import argparse
import asyncio
import contextlib
import hashlib
import json
import os
import struct
import time
from pathlib import Path

HEADER_SIGNATURE = 0xFEED
FOOTER_SIGNATURE = 0xBEEF
QEMU_PROTOCOL_BUTTON = 8
QEMU_PROTOCOL_HEALTH_METRIC = 12
QEMU_PROTOCOL_HEART_RATE = 13
QEMU_PROTOCOL_PEBBLE = 1
WATCH_VERSION_ENDPOINT = 16
WATCH_VERSION_RESPONSE = 1
WATCH_FIRMWARE_VERSION_SIZE = 47
WATCH_SERIAL_SIZE = 12
SYNTHETIC_WATCH_SERIAL = b"QEMU0000001\0"
WATCH_HARDWARE_BY_PLATFORM = {"emery": 18, "gabbro": 21}
BUTTONS = {"back": 1, "up": 2, "select": 4, "down": 8}
QUALITIES = {"off-wrist": -1, "worst": 0, "poor": 1, "acceptable": 2, "good": 3, "excellent": 4}
QEMU_HEALTH_METRIC_STEPS = 0


def qemu_frame(protocol: int, payload: bytes) -> bytes:
    if not 0 <= protocol <= 0xFFFF or len(payload) > 0xFFFF:
        raise ValueError("QEMU frame value is out of range")
    return struct.pack(">HHH", HEADER_SIGNATURE, protocol, len(payload)) + payload + struct.pack(">H", FOOTER_SIGNATURE)


def button_frame(button: str | None) -> bytes:
    if button is None:
        state = 0
    else:
        try:
            state = BUTTONS[button]
        except KeyError as error:
            raise ValueError(f"unknown button: {button}") from error
    return qemu_frame(QEMU_PROTOCOL_BUTTON, bytes((state,)))


def heart_rate_frame(bpm: int, quality: str = "excellent") -> bytes:
    if not 0 <= bpm <= 255:
        raise ValueError("heart rate must be between 0 and 255")
    try:
        encoded_quality = QUALITIES[quality]
    except KeyError as error:
        raise ValueError(f"unknown heart-rate quality: {quality}") from error
    return qemu_frame(QEMU_PROTOCOL_HEART_RATE, struct.pack(">Bb", bpm, encoded_quality))


def steps_frame(count: int) -> bytes:
    if not 0 <= count <= 0x7FFFFFFF:
        raise ValueError("step count must be between 0 and 2147483647")
    return qemu_frame(
        QEMU_PROTOCOL_HEALTH_METRIC,
        struct.pack(">Bi", QEMU_HEALTH_METRIC_STEPS, count),
    )


def patch_watch_version_serial(packet: bytes) -> tuple[bytes, bool]:
    """Give serial-less PebbleOS QEMU watches a stable PebbleKit identity."""
    if len(packet) < 4:
        return packet, False
    payload_length, endpoint = struct.unpack(">HH", packet[:4])
    if len(packet) != payload_length + 4 or endpoint != WATCH_VERSION_ENDPOINT:
        return packet, False

    serial_offset = 4 + 1 + (2 * WATCH_FIRMWARE_VERSION_SIZE) + 4 + 9
    serial_end = serial_offset + WATCH_SERIAL_SIZE
    if (
        payload_length < serial_end - 4
        or packet[4] != WATCH_VERSION_RESPONSE
        or packet[serial_offset:serial_end].rstrip(b"\0")
    ):
        return packet, False

    patched = bytearray(packet)
    patched[serial_offset:serial_end] = SYNTHETIC_WATCH_SERIAL
    return bytes(patched), True


def patch_watch_version_platform(packet: bytes, platform: str | None) -> tuple[bytes, bool]:
    """Replace QEMU's unsupported board code without changing real watch metadata."""
    if platform not in WATCH_HARDWARE_BY_PLATFORM or len(packet) < 4:
        return packet, False
    payload_length, endpoint = struct.unpack(">HH", packet[:4])
    if len(packet) != payload_length + 4 or endpoint != WATCH_VERSION_ENDPOINT:
        return packet, False

    hardware_offset = 4 + 1 + 45
    serial_offset = 4 + 1 + (2 * WATCH_FIRMWARE_VERSION_SIZE) + 4 + 9
    serial_end = serial_offset + WATCH_SERIAL_SIZE
    if (
        payload_length < serial_end - 4
        or packet[4] != WATCH_VERSION_RESPONSE
        or packet[serial_offset:serial_end].rstrip(b"\0")
    ):
        return packet, False

    patched = bytearray(packet)
    patched[hardware_offset] = WATCH_HARDWARE_BY_PLATFORM[platform]
    return bytes(patched), True


class QemuWatchVersionPatcher:
    """Reassemble nested QEMU/Pebble frames and patch only watch-version replies."""

    def __init__(self, platform: str | None = None) -> None:
        self.platform = platform
        self._qemu_buffer = bytearray()
        self._pebble_buffer = bytearray()
        self.serial_patch_count = 0
        self.platform_patch_count = 0

    def feed(self, data: bytes) -> bytes:
        self._qemu_buffer.extend(data)
        output = bytearray()
        while len(self._qemu_buffer) >= 8:
            header, protocol, payload_length = struct.unpack(">HHH", self._qemu_buffer[:6])
            if header != HEADER_SIGNATURE:
                raise ValueError("invalid QEMU frame header")
            frame_length = 8 + payload_length
            if len(self._qemu_buffer) < frame_length:
                break
            frame = bytes(self._qemu_buffer[:frame_length])
            del self._qemu_buffer[:frame_length]
            footer = struct.unpack(">H", frame[-2:])[0]
            if footer != FOOTER_SIGNATURE:
                raise ValueError("invalid QEMU frame footer")
            payload = frame[6:-2]
            if protocol != QEMU_PROTOCOL_PEBBLE:
                output.extend(frame)
                continue
            self._pebble_buffer.extend(payload)
            while len(self._pebble_buffer) >= 4:
                pebble_payload_length = struct.unpack(">H", self._pebble_buffer[:2])[0]
                packet_length = pebble_payload_length + 4
                if len(self._pebble_buffer) < packet_length:
                    break
                packet = bytes(self._pebble_buffer[:packet_length])
                del self._pebble_buffer[:packet_length]
                packet, platform_patched = patch_watch_version_platform(packet, self.platform)
                packet, serial_patched = patch_watch_version_serial(packet)
                if platform_patched:
                    self.platform_patch_count += 1
                if serial_patched:
                    self.serial_patch_count += 1
                output.extend(qemu_frame(QEMU_PROTOCOL_PEBBLE, packet))
        return bytes(output)


class Transcript:
    def __init__(self, path: Path | None):
        self.path = path
        self._lock = asyncio.Lock()

    async def write(self, event: str, **fields: object) -> None:
        if self.path is None:
            return
        record = {"monotonic": round(time.monotonic(), 6), "event": event, **fields}
        line = json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n"
        async with self._lock:
            with self.path.open("a", encoding="utf-8") as output:
                output.write(line)

    async def bytes(self, direction: str, data: bytes) -> None:
        await self.write(
            "bytes",
            direction=direction,
            length=len(data),
            sha256=hashlib.sha256(data).hexdigest(),
        )


class Relay:
    def __init__(
        self,
        qemu_host: str,
        qemu_port: int,
        transcript: Transcript,
        watch_platform: str | None = None,
    ):
        self.qemu_host = qemu_host
        self.qemu_port = qemu_port
        self.transcript = transcript
        self.watch_platform = watch_platform
        self.qemu_writer: asyncio.StreamWriter | None = None
        self.phone_connected = False
        self._write_lock = asyncio.Lock()

    async def inject(self, data: bytes, description: str) -> None:
        if self.qemu_writer is None or self.qemu_writer.is_closing():
            raise RuntimeError("QEMU is not connected")
        async with self._write_lock:
            self.qemu_writer.write(data)
            await self.qemu_writer.drain()
        await self.transcript.write("inject", command=description, length=len(data))

    async def handle_phone(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        if self.phone_connected:
            writer.close()
            await writer.wait_closed()
            return
        self.phone_connected = True
        await self.transcript.write("phone-connected")
        qemu_reader: asyncio.StreamReader
        qemu_writer: asyncio.StreamWriter
        try:
            qemu_reader, qemu_writer = await asyncio.open_connection(self.qemu_host, self.qemu_port)
            self.qemu_writer = qemu_writer

            async def forward(
                source: asyncio.StreamReader,
                target: asyncio.StreamWriter,
                direction: str,
                target_lock: asyncio.Lock | None = None,
                patcher: QemuWatchVersionPatcher | None = None,
            ) -> None:
                while data := await source.read(65536):
                    previous_patch_count = patcher.serial_patch_count if patcher else 0
                    previous_platform_patch_count = patcher.platform_patch_count if patcher else 0
                    forwarded = patcher.feed(data) if patcher else data
                    if target_lock is None:
                        if forwarded:
                            target.write(forwarded)
                            await target.drain()
                    else:
                        async with target_lock:
                            if forwarded:
                                target.write(forwarded)
                                await target.drain()
                    await self.transcript.bytes(direction, data)
                    if patcher and patcher.serial_patch_count != previous_patch_count:
                        await self.transcript.write(
                            "watch-serial-synthesized",
                            count=patcher.serial_patch_count - previous_patch_count,
                        )
                    if (
                        patcher
                        and patcher.platform_patch_count != previous_platform_patch_count
                    ):
                        await self.transcript.write(
                            "watch-platform-synthesized",
                            platform=patcher.platform,
                            count=patcher.platform_patch_count - previous_platform_patch_count,
                        )

            watch_version_patcher = QemuWatchVersionPatcher(self.watch_platform)
            tasks = {
                asyncio.create_task(forward(reader, qemu_writer, "phone-to-qemu", self._write_lock)),
                asyncio.create_task(
                    forward(qemu_reader, writer, "qemu-to-phone", patcher=watch_version_patcher)
                ),
            }
            done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for task in pending:
                task.cancel()
            for task in done:
                task.result()
            await asyncio.gather(*pending, return_exceptions=True)
        except Exception as error:
            await self.transcript.write("disconnect-error", error=type(error).__name__, detail=str(error))
        finally:
            self.qemu_writer = None
            self.phone_connected = False
            writer.close()
            with contextlib.suppress(Exception):
                await writer.wait_closed()
            if "qemu_writer" in locals():
                qemu_writer.close()
                with contextlib.suppress(Exception):
                    await qemu_writer.wait_closed()
            await self.transcript.write("phone-disconnected")

    async def handle_control(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        response: dict[str, object]
        try:
            request = json.loads((await asyncio.wait_for(reader.readline(), timeout=5)).decode("utf-8"))
            command = request.get("command")
            if command == "status":
                response = {"ok": True, "phone_connected": self.phone_connected, "qemu_connected": self.qemu_writer is not None}
            elif command == "button":
                button = request.get("button")
                if button not in BUTTONS:
                    raise ValueError("button must be back, up, select, or down")
                await self.inject(button_frame(str(button)), f"button-down:{button}")
                duration = int(request.get("duration_ms", 100))
                if not 1 <= duration <= 5000:
                    raise ValueError("duration_ms must be between 1 and 5000")
                await asyncio.sleep(duration / 1000)
                await self.inject(button_frame(None), f"button-up:{button}")
                response = {"ok": True}
            elif command == "heart_rate":
                bpm = int(request["bpm"])
                quality = str(request.get("quality", "excellent"))
                await self.inject(heart_rate_frame(bpm, quality), f"heart-rate:{bpm}:{quality}")
                response = {"ok": True}
            elif command == "steps":
                count = int(request["count"])
                await self.inject(steps_frame(count), f"steps:{count}")
                response = {"ok": True}
            else:
                raise ValueError("unknown control command")
        except Exception as error:
            response = {"ok": False, "error": str(error)}
        writer.write((json.dumps(response, sort_keys=True) + "\n").encode("utf-8"))
        await writer.drain()
        writer.close()
        await writer.wait_closed()


async def run(args: argparse.Namespace) -> None:
    control_path = Path(args.control_socket)
    control_path.parent.mkdir(parents=True, exist_ok=True)
    with contextlib.suppress(FileNotFoundError):
        control_path.unlink()
    transcript = Transcript(Path(args.transcript) if args.transcript else None)
    relay = Relay(args.qemu_host, args.qemu_port, transcript, args.watch_platform)
    phone_server = await asyncio.start_server(relay.handle_phone, args.listen_host, args.listen_port)
    control_server = await asyncio.start_unix_server(relay.handle_control, path=control_path)
    os.chmod(control_path, 0o600)
    await transcript.write("ready", listen_port=args.listen_port, qemu_port=args.qemu_port)
    async with phone_server, control_server:
        await asyncio.gather(phone_server.serve_forever(), control_server.serve_forever())


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen-host", default="0.0.0.0")
    parser.add_argument("--listen-port", type=int, default=12344)
    parser.add_argument("--qemu-host", default="127.0.0.1")
    parser.add_argument("--qemu-port", type=int, default=12345)
    parser.add_argument("--control-socket", default="/run/trackglance/relay.sock")
    parser.add_argument("--transcript")
    parser.add_argument("--watch-platform", choices=sorted(WATCH_HARDWARE_BY_PLATFORM))
    asyncio.run(run(parser.parse_args()))


if __name__ == "__main__":
    main()
