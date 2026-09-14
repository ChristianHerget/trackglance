import asyncio
import json
import struct
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

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
    steps_frame,
)


class FrameTest(unittest.TestCase):
    def test_button_frames_match_pebble_qemu_wire_format(self):
        self.assertEqual(button_frame("select").hex(), "feed0008000104beef")
        self.assertEqual(button_frame(None).hex(), "feed0008000100beef")

    def test_heart_rate_frame_matches_pebble_qemu_wire_format(self):
        self.assertEqual(heart_rate_frame(123).hex(), "feed000d00027b04beef")

    def test_step_frame_matches_pebble_qemu_health_metric_wire_format(self):
        self.assertEqual(steps_frame(123).hex(), "feed000c0005000000007bbeef")

    def test_invalid_frame_values_fail_closed(self):
        with self.assertRaises(ValueError):
            qemu_frame(65536, b"")
        with self.assertRaises(ValueError):
            heart_rate_frame(256)
        with self.assertRaises(ValueError):
            steps_frame(-1)
        with self.assertRaises(ValueError):
            steps_frame(0x80000000)

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


class RelayTurnoverTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / 'relay.jsonl'
        self.peers = asyncio.Queue()
        self.writers = []

        async def qemu(reader, writer):
            self.writers.append(writer)
            await self.peers.put((reader, writer))

        self.qemu_server = await asyncio.start_server(qemu, '127.0.0.1', 0)
        self.relay = Relay('127.0.0.1', self.qemu_server.sockets[0].getsockname()[1], Transcript(self.path))
        self.phone_server = await asyncio.start_server(self.relay.handle_phone, '127.0.0.1', 0)

    async def asyncTearDown(self):
        self.phone_server.close()
        self.qemu_server.close()
        await self.phone_server.wait_closed()
        await self.qemu_server.wait_closed()
        task = self.relay._session_task
        if task:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
        for writer in self.writers:
            writer.close()
            await writer.wait_closed()
        self.directory.cleanup()

    async def phone(self):
        reader, writer = await asyncio.open_connection('127.0.0.1', self.phone_server.sockets[0].getsockname()[1])
        self.writers.append(writer)
        return reader, writer

    async def connected(self):
        phone = await self.phone()
        peer = await asyncio.wait_for(self.peers.get(), 2)
        await self.until(lambda: self.relay.qemu_writer is not None)
        return phone, peer

    async def until(self, predicate):
        async with asyncio.timeout(2):
            while not predicate():
                await asyncio.sleep(0.001)

    def records(self):
        return [json.loads(line) for line in self.path.read_text().splitlines()]

    async def test_half_open_phone_is_replaced_and_both_old_sockets_close(self):
        (old_phone, _), (old_qemu, _) = await self.connected()
        (_, phone), (qemu, _) = await self.connected()
        self.assertEqual(await asyncio.wait_for(old_phone.read(), 2), b'')
        self.assertEqual(await asyncio.wait_for(old_qemu.read(), 2), b'')
        phone.write(b'new-session')
        await phone.drain()
        self.assertEqual(await asyncio.wait_for(qemu.readexactly(11), 2), b'new-session')
        self.assertTrue(self.relay.phone_connected)
        self.assertEqual(self.relay.session_id, 2)

    async def test_rapid_reconnects_keep_only_newest_session(self):
        for _ in range(12):
            (_, phone), (qemu, _) = await self.connected()
        await self.relay.inject(b'latest', 'latest')
        self.assertEqual(await asyncio.wait_for(qemu.readexactly(6), 2), b'latest')
        self.assertEqual(self.relay.reconnect_count, 11)

    async def test_stale_cleanup_cannot_clear_replacement(self):
        await self.connected()
        old_task = self.relay._session_task
        await self.connected()
        await asyncio.gather(old_task, return_exceptions=True)
        self.assertTrue(self.relay.phone_connected)
        self.assertIsNotNone(self.relay.qemu_writer)
        self.assertIsNot(self.relay._session_task, old_task)
        self.assertEqual(self.relay.last_disconnect_reason, 'replaced')

    async def test_reconnect_during_eof_cleanup_does_not_cancel_cleanup_again(self):
        original = asyncio.open_connection
        retiring = asyncio.Event()
        finish = asyncio.Event()

        class SlowCancellationReader:
            def __init__(self, reader):
                self.reader = reader

            async def read(self, size):
                try:
                    return await self.reader.read(size)
                except asyncio.CancelledError:
                    retiring.set()
                    await finish.wait()
                    raise

        async def connect(host, port):
            reader, writer = await original(host, port)
            if port == self.relay.qemu_port:
                reader = SlowCancellationReader(reader)
            return reader, writer

        with patch('relay.asyncio.open_connection', side_effect=connect):
            (_, phone), _ = await self.connected()
        old_task = self.relay._session_task
        phone.close()
        await asyncio.wait_for(retiring.wait(), 2)
        await self.connected()
        self.assertFalse(old_task.done())
        finish.set()
        await asyncio.wait_for(old_task, 2)
        self.assertTrue(self.relay.phone_connected)
        self.assertEqual(self.relay.session_id, 2)
        self.assertTrue(any(r['event'] == 'phone-disconnected' and r['session_id'] == 1
                            for r in self.records()))

    async def test_injection_queued_during_turnover_targets_new_session(self):
        await self.connected()
        async with self.relay._session_lock:
            await self.phone()
            await asyncio.sleep(0.01)
            injection = asyncio.create_task(self.relay.inject(b'x', 'turnover'))
        # Connecting QEMU may still be pending; injection must fail cleanly or target session 2.
        result = await asyncio.gather(injection, return_exceptions=True)
        peer, _ = await asyncio.wait_for(self.peers.get(), 2)
        await self.until(lambda: self.relay.qemu_writer is not None)
        if isinstance(result[0], RuntimeError):
            await self.relay.inject(b'x', 'turnover')
        self.assertEqual(await asyncio.wait_for(peer.readexactly(1), 2), b'x')
        self.assertEqual([r['session_id'] for r in self.records() if r['event'] == 'inject'], [2])

    async def test_injection_backpressure_does_not_block_session_replacement(self):
        await self.connected()
        draining = asyncio.Event()
        release = asyncio.Event()

        async def drain():
            draining.set()
            await release.wait()

        self.relay.qemu_writer.drain = drain
        injection = asyncio.create_task(self.relay.inject(b'x', 'backpressure'))
        await asyncio.wait_for(draining.wait(), 2)
        await asyncio.wait_for(self.connected(), 2)
        self.assertEqual(self.relay.session_id, 2)
        release.set()
        await injection
        events = [(r['event'], r['session_id']) for r in self.records()]
        self.assertLess(events.index(('inject', 1)), events.index(('session-replaced', 1)))

    async def test_qemu_connection_failure_clears_only_failed_session(self):
        self.qemu_server.close()
        await self.qemu_server.wait_closed()
        reader, _ = await self.phone()
        self.assertEqual(await asyncio.wait_for(reader.read(), 2), b'')
        await self.until(lambda: not self.relay.phone_connected)
        self.assertIsNone(self.relay.qemu_writer)
        self.assertEqual(self.relay.last_disconnect_reason, 'qemu-connect-failed')
        with self.assertRaises(RuntimeError):
            await self.relay.inject(b'x', 'disconnected')

    async def test_transcript_orders_each_session_and_records_disconnect_reasons(self):
        await self.connected()
        old_task = self.relay._session_task
        await self.connected()
        await asyncio.gather(old_task, return_exceptions=True)
        await self.relay.inject(b'x', 'test')
        records = self.records()
        events = [(r['event'], r['session_id']) for r in records]
        self.assertLess(events.index(('phone-connected', 1)), events.index(('session-replaced', 1)))
        self.assertLess(events.index(('session-replaced', 1)), events.index(('phone-connected', 2)))
        self.assertLess(events.index(('qemu-connected', 2)), events.index(('inject', 2)))
        disconnected = next(r for r in records if r['event'] == 'phone-disconnected')
        self.assertEqual(disconnected['reason'], 'replaced')
        self.assertEqual(disconnected['session_id'], 1)
        self.assertEqual(sorted(r['monotonic'] for r in records), [r['monotonic'] for r in records])


if __name__ == "__main__":
    unittest.main()
