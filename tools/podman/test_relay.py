import asyncio
import json
import struct
import tempfile
import unittest
from pathlib import Path

from relay import (
    QEMU_PROTOCOL_PEBBLE,
    SYNTHETIC_WATCH_SERIAL,
    QemuWatchVersionPatcher,
    Relay,
    Transcript,
    button_frame,
    heart_rate_frame,
    patch_watch_version_platform,
    patch_watch_version_serial,
    qemu_frame,
)


class FrameTest(unittest.TestCase):
    def test_button_frames_match_pebble_qemu_wire_format(self):
        self.assertEqual(button_frame("select").hex(), "feed0008000104beef")
        self.assertEqual(button_frame(None).hex(), "feed0008000100beef")

    def test_heart_rate_frame_matches_pebble_qemu_wire_format(self):
        self.assertEqual(heart_rate_frame(123).hex(), "feed000d00027b04beef")

    def test_invalid_frame_values_fail_closed(self):
        with self.assertRaises(ValueError):
            qemu_frame(65536, b"")
        with self.assertRaises(ValueError):
            heart_rate_frame(256)

    def watch_version_packet(self, serial: bytes = b"\0" * 12) -> bytes:
        payload = bytearray(134)
        payload[0] = 1
        payload[108:120] = serial
        return struct.pack(">HH", len(payload), 16) + payload

    def test_empty_qemu_watch_serial_is_synthesized(self):
        packet = self.watch_version_packet()
        patched, changed = patch_watch_version_serial(packet)
        self.assertTrue(changed)
        self.assertEqual(patched[112:124], SYNTHETIC_WATCH_SERIAL)

    def test_real_watch_serial_is_preserved(self):
        packet = self.watch_version_packet(b"REALWATCH01\0")
        patched, changed = patch_watch_version_serial(packet)
        self.assertFalse(changed)
        self.assertEqual(patched, packet)

    def test_empty_qemu_watch_platform_is_synthesized(self):
        packet = bytearray(self.watch_version_packet())
        packet[50] = 245
        patched, changed = patch_watch_version_platform(bytes(packet), "gabbro")
        self.assertTrue(changed)
        self.assertEqual(patched[50], 21)

    def test_real_watch_platform_is_preserved(self):
        packet = bytearray(self.watch_version_packet(b"REALWATCH01\0"))
        packet[50] = 18
        patched, changed = patch_watch_version_platform(bytes(packet), "gabbro")
        self.assertFalse(changed)
        self.assertEqual(patched, bytes(packet))

    def test_fragmented_qemu_and_pebble_frames_are_reassembled(self):
        packet = self.watch_version_packet()
        wire = qemu_frame(QEMU_PROTOCOL_PEBBLE, packet[:37]) + qemu_frame(
            QEMU_PROTOCOL_PEBBLE, packet[37:]
        )
        patcher = QemuWatchVersionPatcher("gabbro")
        output = bytearray()
        for boundary in (3, 11, 29, 61, len(wire)):
            output.extend(patcher.feed(wire[:boundary]))
            wire = wire[boundary:]
        output.extend(patcher.feed(wire))
        expected_packet, _ = patch_watch_version_platform(packet, "gabbro")
        expected_packet, _ = patch_watch_version_serial(expected_packet)
        self.assertEqual(bytes(output), qemu_frame(QEMU_PROTOCOL_PEBBLE, expected_packet))
        self.assertEqual(patcher.serial_patch_count, 1)
        self.assertEqual(patcher.platform_patch_count, 1)

    def test_non_pebble_qemu_frame_is_unchanged(self):
        frame = heart_rate_frame(72)
        self.assertEqual(QemuWatchVersionPatcher().feed(frame), frame)


class RelayTest(unittest.IsolatedAsyncioTestCase):
    async def test_phone_stream_is_transparent_and_control_is_injected(self):
        received = bytearray()
        qemu_connected = asyncio.Event()

        async def qemu(reader, writer):
            qemu_connected.set()
            while data := await reader.read(1024):
                received.extend(data)
            writer.close()

        qemu_server = await asyncio.start_server(qemu, "127.0.0.1", 0)
        qemu_port = qemu_server.sockets[0].getsockname()[1]
        with tempfile.TemporaryDirectory() as directory:
            transcript = Transcript(Path(directory) / "transcript.jsonl")
            relay = Relay("127.0.0.1", qemu_port, transcript)
            phone_server = await asyncio.start_server(relay.handle_phone, "127.0.0.1", 0)
            phone_port = phone_server.sockets[0].getsockname()[1]
            _, phone_writer = await asyncio.open_connection("127.0.0.1", phone_port)
            await asyncio.wait_for(qemu_connected.wait(), 2)
            phone_writer.write(b"phone-payload")
            await phone_writer.drain()
            for _ in range(100):
                if received == b"phone-payload":
                    break
                await asyncio.sleep(0.005)
            await relay.inject(button_frame("up"), "test")
            await asyncio.sleep(0.05)
            phone_writer.close()
            await phone_writer.wait_closed()
            for _ in range(100):
                if not relay.phone_connected:
                    break
                await asyncio.sleep(0.005)
            self.assertEqual(received, b"phone-payload" + button_frame("up"))
            records = [json.loads(line) for line in (Path(directory) / "transcript.jsonl").read_text().splitlines()]
            self.assertTrue(any(record["event"] == "inject" for record in records))
            phone_server.close()
            await phone_server.wait_closed()
        qemu_server.close()
        await qemu_server.wait_closed()


if __name__ == "__main__":
    unittest.main()
