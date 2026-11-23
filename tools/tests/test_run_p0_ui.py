"""Host-runner safety checks; these do not execute or claim Android UI coverage."""
import importlib.util
import contextlib
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("p0_runner", Path(__file__).resolve().parents[1] / "run-p0-ui.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class HostRunnerTest(unittest.TestCase):
    def test_physical_device_is_rejected_before_any_adb_or_build_command(self):
        with patch.object(sys, "argv", ["run-p0-ui.py", "--serial", "physical-phone"]), \
                patch.object(runner.subprocess, "run") as execute, tempfile.TemporaryDirectory() as output:
            sys.argv += ["--output", output]
            with self.assertRaisesRegex(RuntimeError, "Refusing to run"):
                runner.main()
            execute.assert_not_called()

    def exercise_cleanup(self, instrumentation_fails):
        permissions = ["android.permission." + p for p in ("READ_CONTACTS", "WRITE_CONTACTS", "READ_MEDIA_AUDIO")]
        grants = {p: False for p in permissions}
        flags = {p: set() for p in permissions}
        # Preserve a mixed starting state, including a user-set flag and developer option ON.
        grants[permissions[0]] = True
        flags[permissions[0]].add("USER_SET")
        original = (grants.copy(), {p: f.copy() for p, f in flags.items()})
        mode = ["1"]
        executed = []

        def run(command, **kwargs):
            executed.append(command)
            text = ""
            if command[0] == "adb":
                self.assertEqual(command[1:3], ["-s", "emulator-5554"])
                args = command[3:]
                if args[:2] == ["shell", "getprop"]:
                    text = {"ro.kernel.qemu": "1", "sys.boot_completed": "1", "ro.build.version.sdk": "35"}[args[2]]
                elif args[:3] == ["shell", "settings", "get"]:
                    text = mode[0]
                elif args[:3] == ["shell", "settings", "put"]:
                    mode[0] = args[-1]
                elif args[:3] == ["shell", "dumpsys", "package"]:
                    text = "\n".join(f"{p}: granted={str(grants[p]).lower()}, flags=[{'|'.join(flags[p])}]" for p in permissions)
                elif args[0] == "install":
                    text = "Success"
                elif args[:2] == ["shell", "pm"]:
                    operation, permission = args[2], args[4]
                    if operation in ("grant", "revoke"):
                        grants[permission] = operation == "grant"
                    elif operation == "clear-permission-flags":
                        flags[permission].difference_update(f.upper().replace("-", "_") for f in args[5:])
                    elif operation == "set-permission-flags":
                        flags[permission].update(f.upper().replace("-", "_") for f in args[5:])
                elif args[:3] == ["shell", "am", "instrument"]:
                    mode[0] = "0"
                    grants.update({p: True for p in permissions})
                    flags[permissions[-1]].add("USER_SET")
                    count = 8 if args[-2].endswith("P0RingtoneLifecycleUiTest") else 1
                    kwargs["stdout"].write("FAILURES!!!" if instrumentation_fails else f"OK ({count} tests)")
            return subprocess.CompletedProcess(command, 0, text, "")

        with tempfile.TemporaryDirectory() as output, \
                patch.object(sys, "argv", ["run-p0-ui.py", "--output", output]), \
                patch.object(runner.subprocess, "run", side_effect=run), \
                patch.object(runner.subprocess, "Popen"), contextlib.redirect_stdout(io.StringIO()):
            if instrumentation_fails:
                with self.assertRaisesRegex(RuntimeError, "P0RingtoneLifecycleUiTest"):
                    runner.main()
            else:
                runner.main()
        self.assertEqual(mode[0], "1")
        self.assertEqual((grants, flags), original)
        self.assertEqual(sum(c[:6] == ["adb", "-s", "emulator-5554", "shell", "am", "instrument"] for c in executed), 4)

    def test_success_restores_original_grants_flags_and_developer_option(self):
        self.exercise_cleanup(False)

    def test_failure_is_reported_and_still_restores_original_state(self):
        self.exercise_cleanup(True)


if __name__ == "__main__":
    unittest.main()
