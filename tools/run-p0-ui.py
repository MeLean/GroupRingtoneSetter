#!/usr/bin/env python3
"""Dedicated emulator only. Restore grants outside instrumentation (revocation kills its process)."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.milen.grounpringtonesetter"
CLASS = PACKAGE + ".ui."


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--output", type=Path, default=Path("/private/tmp/grs-p0-ui-self-contained"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    def adb(*cmd):
        result = subprocess.run(["adb", "-s", args.serial, *cmd], text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=30)
        if result.returncode:
            raise RuntimeError(f"adb -s {args.serial} {' '.join(cmd)}\n{result.stderr}{result.stdout}")
        return result.stdout.strip()

    if not args.serial.startswith("emulator-") or adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Refusing to run: select a dedicated Android emulator, never a physical phone")
    if adb("shell", "getprop", "sys.boot_completed") != "1":
        raise RuntimeError("SETUP: emulator is not fully booted")
    sdk = int(adb("shell", "getprop", "ro.build.version.sdk"))
    audio = ["READ_MEDIA_AUDIO"] if sdk >= 33 else ["READ_EXTERNAL_STORAGE"]
    if sdk < 29:
        audio.append("WRITE_EXTERNAL_STORAGE")
    permissions = ["android.permission." + p for p in ["READ_CONTACTS", "WRITE_CONTACTS", *audio]]
    original_mode = adb("shell", "settings", "get", "global", "always_finish_activities")
    if original_mode not in ("null", "0", "1"):
        raise RuntimeError(f"SETUP: invalid developer setting: {original_mode}")
    # Assemble/install first. Never install on other connected devices. Do not uninstall to repair signatures.
    env = {**os.environ, "ANDROID_SERIAL": args.serial}
    with (args.output / "build.log").open("w") as output:
        result = subprocess.run(["./gradlew", ":app:assembleDebug", ":app:assembleDebugAndroidTest"],
                                cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f"Build blocked/failed: see {args.output / 'build.log'}")
    for apk in [ROOT / "app/build/outputs/apk/debug/app-debug.apk",
                ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
        output = adb("install", "-r", "-t", str(apk))
        if "Success" not in output:
            raise RuntimeError(f"SETUP: installation failed: {output}")
    package_dump = adb("shell", "dumpsys", "package", PACKAGE)
    (args.output / "original-package-state.txt").write_text(package_dump)
    grants = {}
    for permission in permissions:
        match = re.search(re.escape(permission) + r": granted=(true|false), flags=\[([^\]]*)\]", package_dump)
        if not match:
            raise RuntimeError(f"SETUP: cannot snapshot runtime grant {permission}")
        grants[permission] = (match[1] == "true", match[2])
    failures = []
    logcat = None
    try:
        logcat_file = (args.output / "logcat.txt").open("w")
        logcat = subprocess.Popen(["adb", "-s", args.serial, "logcat", "-v", "threadtime", "-T", "1"],
                                  stdout=logcat_file, stderr=subprocess.STDOUT)
        cases = ["P0RingtoneLifecycleUiTest",
                 "P0AudioPermissionUiTest#grantedAudioOpensSourceWithoutPermissionDialog",
                 "P0AudioPermissionUiTest#deniedAudioDoesNotAssignOrAdvertise",
                 "P0AudioPermissionUiTest#deniedAudioCanBeRequestedAgainAndGranted"]
        for index, case in enumerate(cases):
            # Each permission test starts fresh; functional cases grant and verify in their setup.
            adb("shell", "am", "force-stop", PACKAGE)
            if "#denied" in case:
                for permission in ["android.permission." + p for p in audio]:
                    adb("shell", "pm", "revoke", PACKAGE, permission)
                    adb("shell", "pm", "clear-permission-flags", PACKAGE, permission, "user-set", "user-fixed")
            command = ["adb", "-s", args.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class",
                       CLASS + case, PACKAGE + ".test/com.milen.grounpringtonesetter.testing.RegressionTestRunner"]
            with (args.output / f"instrumentation-{index}.txt").open("w") as output:
                run = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=1200)
            report = (args.output / f"instrumentation-{index}.txt").read_text()
            expected_count = 8 if case == "P0RingtoneLifecycleUiTest" else 1
            if run.returncode or "FAILURES!!!" in report or not re.search(r"OK \(" + str(expected_count) + r" tests?\)", report):
                failures.append(case)
            print(f"{'FAILED' if case in failures else 'PASSED'}: {case}", flush=True)
    finally:
        # Every cleanup is attempted even if one fails; retain original test failures as well.
        cleanups = [("home", lambda: adb("shell", "input", "keyevent", "KEYCODE_HOME")),
                    ("stop app", lambda: adb("shell", "am", "force-stop", PACKAGE)),
                    ("developer option", lambda: adb("shell", "settings", "delete", "global", "always_finish_activities")
                     if original_mode == "null" else adb("shell", "settings", "put", "global", "always_finish_activities", original_mode))]
        for permission, (granted, flags) in grants.items():
            cleanups.append((permission, lambda p=permission, g=granted: adb("shell", "pm", "grant" if g else "revoke", PACKAGE, p)))
            cleanups.append((permission + " flags", lambda p=permission: adb("shell", "pm", "clear-permission-flags", PACKAGE, p, "user-set", "user-fixed")))
            saved_flags = [flag for flag in ["user-set", "user-fixed"] if flag.upper().replace("-", "_") in flags]
            if saved_flags:
                cleanups.append((permission + " restore flags", lambda p=permission, f=saved_flags: adb("shell", "pm", "set-permission-flags", PACKAGE, p, *f)))
        for label, cleanup in cleanups:
            try:
                cleanup()
            except Exception as error:
                failures.append(f"CLEANUP {label}: {error}")
        if logcat:
            logcat.terminate()
            logcat.wait(timeout=10)
            logcat_file.close()
        restored = adb("shell", "dumpsys", "package", PACKAGE)
        (args.output / "restored-package-state.txt").write_text(restored)
        for permission, (granted, flags) in grants.items():
            match = re.search(re.escape(permission) + r": granted=(true|false), flags=\[([^\]]*)\]", restored)
            if not match or (match[1] == "true") != granted:
                failures.append(f"CLEANUP: grant restoration mismatch: {permission}")
            elif any((flag in flags) != (flag in match[2]) for flag in ("USER_SET", "USER_FIXED")):
                failures.append(f"CLEANUP: permission flag restoration mismatch: {permission}")
        if adb("shell", "settings", "get", "global", "always_finish_activities") != original_mode:
            failures.append("CLEANUP: developer option restoration mismatch")
    if failures:
        raise RuntimeError("\n".join(failures))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.TimeoutExpired) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
