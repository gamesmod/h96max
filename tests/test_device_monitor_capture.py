"""Ошибки захвата не должны превращаться в чистый сетевой отчёт."""
import importlib.util
import pathlib
import subprocess
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("device_monitor", ROOT / "tools/device-monitor.py")
MONITOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MONITOR)


class CaptureTests(unittest.TestCase):
    def test_adb_selection_requires_online_matching_device(self):
        listing = "List of devices attached\none\tdevice\ntwo\toffline\n"
        self.assertEqual(MONITOR.select_adb_device(None, listing), "one")
        self.assertEqual(MONITOR.select_adb_device("one", listing), "one")
        for serial in ("two", "nonexistent-secret-id"):
            with self.subTest(serial=serial):
                with self.assertRaises(ValueError) as error:
                    MONITOR.select_adb_device(serial, listing)
                self.assertNotIn(serial, str(error.exception))
        with self.assertRaises(ValueError):
            MONITOR.select_adb_device(None, listing + "three\tdevice\n")
        with self.assertRaises(ValueError):
            MONITOR.select_adb_device(None, "List of devices attached\none\tunauthorized\n")

    def test_early_tcpdump_failure_is_not_an_empty_success(self):
        process = mock.Mock()
        process.poll.return_value = 1
        with mock.patch.object(MONITOR.subprocess, "Popen", return_value=process), \
             mock.patch.object(MONITOR.time, "sleep"), \
             mock.patch.object(MONITOR.os, "unlink", wraps=MONITOR.os.unlink) as unlink:
            with self.assertRaises(OSError):
                MONITOR.traffic_capture("test0", 1)
            removed_pcaps = [call.args[0] for call in unlink.call_args_list
                             if pathlib.Path(call.args[0]).name.startswith("h96-traffic-")]
            self.assertEqual(len(removed_pcaps), 1)
            self.assertFalse(pathlib.Path(removed_pcaps[0]).exists())

    def test_unreadable_or_empty_capture_is_not_success(self):
        for returncode, output in ((1, ""), (0, "")):
            with self.subTest(returncode=returncode):
                process = mock.Mock()
                process.poll.side_effect = [None, 0]
                process.wait.return_value = 0
                result = subprocess.CompletedProcess([], returncode, stdout=output)
                with mock.patch.object(MONITOR.subprocess, "Popen", return_value=process), \
                     mock.patch.object(MONITOR.subprocess, "run", return_value=result), \
                     mock.patch.object(MONITOR.time, "sleep"):
                    with self.assertRaises(OSError):
                        MONITOR.traffic_capture("test0", 1)


if __name__ == "__main__":
    unittest.main()
