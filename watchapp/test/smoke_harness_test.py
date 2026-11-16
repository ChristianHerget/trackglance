import importlib.util
from pathlib import Path
import unittest
from unittest import mock
from uuid import UUID

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("watch_smoke", ROOT / "tools/podman/watch-smoke.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class SmokeHarnessTest(unittest.TestCase):
    def runtime(self):
        return smoke.Smoke(mock.Mock(), UUID(int=1), 5, "0.2.8", mock.Mock())

    def test_activity_context_waits_for_packaged_javascript_readiness(self):
        runtime = self.runtime()
        sent = []
        calls = 0

        def receive():
            nonlocal calls
            if calls == 0:
                self.assertEqual(sent, [])
                runtime.pkjs_ready.set()
            calls += 1

        runtime.receive = receive
        runtime.send = lambda kind, fields: sent.append((kind, fields))
        runtime.until(lambda: runtime.context_sent, "ready", timeout=1)
        self.assertEqual([item[1][5].value for item in sent], [0, 1])

    def test_missing_javascript_readiness_expires_without_publishing_activity(self):
        runtime = self.runtime()
        runtime.receive = lambda: None
        runtime.send = mock.Mock()
        with mock.patch.object(smoke.time, "monotonic", side_effect=[0, 0, 2]):
            with self.assertRaisesRegex(TimeoutError, "not ready"):
                runtime.until(lambda: runtime.context_sent, "not ready", timeout=1)
        runtime.send.assert_not_called()


if __name__ == "__main__":
    unittest.main()
