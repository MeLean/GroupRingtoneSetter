# Self-contained P0 UI test environment

Production code, production manifests, dependencies and existing staged work were not changed by this task. All changes remain local. This is test infrastructure, not confirmation that the P0 application defects are resolved.

## Confirmed infrastructure gaps

- P0 previously granted contacts permission only. Home checks SDK-specific audio access before opening the ringtone source selector; the test did not satisfy this prerequisite.
- The system picker test selected the third row rather than a known fixture.
- File selection inserted a fixture but searched whichever DocumentsUI location happened to be open.
- OFF and ON previously ran inside each test method. A failure during OFF prevented ON from executing for that method.
- The fake tone manager's selected-tone map survived `resetUiScenario()`, although its operation history was cleared.

These are source-confirmed gaps. The user's observed permission stalls and missing-file behavior have not been reproduced here because device execution is blocked.

## Changes in this task

- `P0UiEnvironment`: emulator-only guard; verified contacts and SDK-appropriate audio grants; known fake state; original developer-option snapshot/restoration; readable, published MediaStore fixtures; explicit setup errors; bounded synchronization; real system picker selection by fixture title; DocumentsUI Audio-root navigation and search; exact owned-URI cleanup in `use`, including failed scenarios. Known external picker tasks are closed. No WRITE_SETTINGS app-op or real system tone is changed; original system tones are checked unchanged during cleanup.
- `AudioFixtures`: adds two distinguishable 2.5-second, mono, 16-bit PCM WAV signals (440/660 Hz). Existing contract-test generator is retained.
- `P0RingtoneLifecycleUiTest`: separate parameterized OFF/ON cases (8 total); known system fixture URI assertions; two successive file selections with byte-for-byte comparison to the known MediaStore fixture; existing operation-count, advertisement-count and Activity-destruction assertions retained; no completion dialog remains after acknowledgement. DocumentsUI may return a document URI rather than the MediaStore URI, so file assertions compare readable contents, not incompatible URI representations.
- `P0AudioPermissionUiTest`: three independent cases for granted audio access, denial without assignment/advertising, and denial followed by another request and grant. These use real permission-controller UI. Contact permission is granted and verified as a functional prerequisite. Existing component coverage for fake WRITE_SETTINGS denial remains unchanged.
- Fake tone-manager reset now clears selected values as well as history.
- `tools/run-p0-ui.py`: single-command build/install/run on the selected booted emulator only; no uninstall/signature workaround; captures instrumentation and Logcat; runs denied permission cases in separate instrumentation processes with known permission flags; restores original grants, user-set/user-fixed flags and developer option outside the app process. Android runtime permission revocation can kill the app process, so it must not be performed by the running instrumentation itself. Use this wrapper when permission restoration is required, rather than invoking the test class directly.
- Three host-runner safety tests cover physical-device rejection, restoration after success and restoration after failed instrumentation. They mock subprocesses; their success does not indicate Android tests passed.

Fake contacts/groups never write personal/provider contact rows. The suite uses fake advertisement and default-tone gateways; it does not display real Google ads or change real default ringtone/notification/alarm settings. The real external picker, MediaStore fixture publication/readback and Activity Result lifecycle journeys are retained.

## Run on the dedicated emulator

```bash
python3 tools/run-p0-ui.py --serial emulator-5554
```

Requires an already booted disposable emulator and Android SDK/Gradle tooling. No manual audio files, contacts, grants or Developer Options preparation is required by the runner. Output defaults to `/private/tmp/grs-p0-ui-self-contained`. It refuses physical-device serials. A build/install or prerequisite error aborts explicitly. A signature mismatch requires a separate decision about the disposable emulator; the runner never uninstalls existing app data.

The functional class can still be targeted with the normal Gradle class filter, but use the wrapper for permission snapshot/restoration and the separate initially-denied tests. Each parameterized functional case snapshots/restores `always_finish_activities` in guaranteed cleanup, so a failed OFF case does not suppress its ON counterpart.

## Actual verification

- `git diff --check`: passed.
- `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/tests -v`: 3 passed. Mocked host commands only.
- Supplemental direct Kotlin compilation of all Android-test sources against installed cached artifacts: passed (one existing deprecated `getParcelableExtra` warning). This is not a Gradle build, APK installation or UI execution.
- Executed the compiled PCM generator and inspected both files with Python's WAV decoder: both are 40,000 frames at 16,000 Hz, mono, 16-bit PCM, 2.5 seconds, nonzero signal, with distinct contents. The first standalone Java invocation lacked Kotlin stdlib on its runtime classpath; after supplying it, this check passed.
- `./gradlew :app:compileDebugAndroidTestKotlin`: BLOCKED before compilation by the Gradle wrapper lock permission error recorded in `verification.txt`.
- `python3 tools/run-p0-ui.py --serial emulator-5554`: BLOCKED at the first ADB emulator check by `Operation not permitted` creating the ADB socket listener. No build, install, grant, mode change or instrumentation was performed by this attempt. No retries/restart loop was used.

| Scenario | Don't keep activities | Result |
| --- | --- | --- |
| Default ringtone/notification/alarm system picker | OFF and ON | BLOCKED / UNVERIFIED |
| Six repeated group operations / available-unavailable fake ad gateway | OFF and ON | BLOCKED / UNVERIFIED |
| System and file cancellation | OFF and ON | BLOCKED / UNVERIFIED |
| Two distinct custom audio selections | OFF and ON | BLOCKED / UNVERIFIED |
| Granted / denied / requested-again audio UI | OFF | BLOCKED / UNVERIFIED |

Neither developer-option value was exercised by this execution attempt. No device tests passed or failed; they did not start. Real Google Mobile Ads rendering/impressions, real system tone writes and physical-device behavior remain outside the fake-gateway suite.

## Remaining limits and risks

- Clean-emulator acceptance is UNVERIFIED. DocumentsUI Audio-root/search resources and system ringtone-picker layout must be validated on the target AVD; unsupported picker variants fail explicitly rather than selecting another file or skipping assertions.
- System picker fixture discovery after MediaStore publication needs actual device verification. Readback and published-row checks are implemented, but are not substitutes for a successful real picker journey.
- Normal exception/assertion cleanup is guaranteed by `use` and the host runner's `finally`. If the emulator, host or test process is forcibly killed, fixture cleanup may not execute; the test never performs broad media/contact deletion. Permission/developer-option restoration needs a reachable emulator. Failures are reported rather than concealed.
- Original runtime permissions and user-set/user-fixed flags are restored after instrumentation. Install/system-managed permission flags are not rewritten. Special WRITE_SETTINGS permission is neither granted nor changed by this fake-tone suite.
- Existing ringtone/ad/consent changes and contact-count UI changes are pre-existing local work and are included in the full review snapshot, not authored by this infrastructure task.

`task.patch` contains only this task's changes relative to its starting working tree. `full-review.patch` contains the complete current source/test/tool/document changes relative to HEAD, including untracked text files, excluding generated review patch files themselves to avoid recursive inclusion. No binaries, audio artifacts or private configuration are included. Git status/stat are in `git-state.txt`; normal git statistics omit untracked files.
