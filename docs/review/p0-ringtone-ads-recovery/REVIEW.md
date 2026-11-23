# P0 ringtone and advertising recovery — independent review

Date: 2026-11-23. Local implementation; no release or deployment performed.
No skills, staging commands, commits, pushes, merges, resets, checkouts, rebases,
branches or tags were used. Earlier source changes and review artifacts remain.

**Device verification is UNVERIFIED. These P0 release blockers are not certified
resolved until the real Android picker, Activity lifecycle and GMA scenarios pass.**
JVM results and Android-test compilation are separate evidence, not device proof.

## Root causes and attribution

| Classification | Finding / exact break in the path |
| --- | --- |
| Confirmed legacy restriction; intentionally removed | The process-wide policy rejected another show for 90 seconds after an impression and rejected all shows after four impressions. This deterministically explains an immediate second assignment skipping its ad, and later operations being suppressed after four. It does not establish every cause of real-world no-fill or low Show Rate. |
| Recent optimization consistent with the previous requirements, now obsolete | Load eligibility was also made session-cap-aware. Consequently no next preload could happen after impression four. This was requested in the previous task; this P0 request explicitly reverses that business rule. |
| Confirmed pre-existing assignment defect | Default Tones launcher callbacks read Fragment-local `activePickerToneType` / `activeFilePickerToneType` / pending legacy-permission type. A new Fragment initialized these to null, then returned before calling the assignment ViewModel. The original source already had this weakness. |
| Confirmed pre-existing group restoration defect | Home selected the ringtone group only through `_selectingGroup` in memory. A new ViewModel receiving a restored picker result had no selected group and returned without applying it. |
| Confirmed feedback limitation of the recent follow-up | The pending Default Tones success record survived a retained ViewModel/configuration change, but had no saved-state backing for Activity destruction that clears that ViewModel. Buffered events alone also cannot restore completed feedback into a new ViewModel. |
| Confirmed existing ad/primary-operation coupling | A cache miss retained an operation callback until a network load completed, then recursively attempted a show. Home also emitted its ad-unavailable purchase dialog after actual ringtone success when display failed. These paths could delay feedback, present later, or make an ad failure look like an operation failure. |
| Lifecycle risk confirmed in code, actual SDK failure not reproduced | Per-view/helper inventory and loading Activity context could be lost or obsolete after recreation. Merely discarding every preload on each external-picker destruction would leave repeated Don't-keep-activities returns cold. Application-owned placement inventory now avoids that lifecycle dependence. |
| Suspected / unproven | Missing SDK terminal callbacks, invalid OEM picker behavior, UMP form interruption, no-fill/demand and country-specific revenue effects require device/runtime evidence. No production revenue trace establishes that they caused the reported incidents. |

## Intentionally removed advertising limits

`InterstitialAdPolicy` no longer contains the 90-second display interval,
four-impression limit, impression counter, last-impression timestamp or related
load/show skip conditions. Its impression-update hook and obsolete cap tests were
removed. It retains only the global full-screen exclusion and load-failure backoff
(30/60/120/300 seconds, capped at 300, reset by load success).

Operation IDs and one-attempt-per-operation state prevent duplicates. They are
not session/day counters and impose no waiting period on a new successful
assignment. Twelve synthetic immediate show/impression/dismiss cycles use twelve
different consumed ads and successful next preloads without advancing the clock.

## New end-to-end behavior

1. A picker result must claim its saved target once. Cancellation consumes the
   pending identity without applying a tone. A duplicate result cannot assign or
   advertise twice. Missing/invalid identity fails gracefully rather than claiming
   success. A missing non-silent ringtone result also fails rather than silently
   assigning null.
2. The repository/tone manager performs the actual assignment. Only success
   records a completion ID. Home validation uses application context and does not
   abort merely because the originally passed Activity was destroyed.
3. The matching screen observes pending completion only while its view is
   RESUMED. It checks current entitlement, then attempts one available cached ad.
   Consent/SDK readiness and ownership remain authoritative inside the helper.
4. A cache miss completes the operation immediately and preloads for a future
   operation. There is no retained network-to-show callback, so a later load
   cannot display the old operation's ad on another screen. A failed display still
   yields normal ringtone-success feedback, with existing diagnostic tracking.
5. Showing consumes the cached object before calling the SDK. Duplicate SDK
   terminal callbacks are ignored. Dismissal/failure releases the full-screen
   lease once and attempts the next preload; resumption/picker opening also
   reestablishes preload readiness, with in-flight request coalescing.
6. View destruction resolves that screen's waiting feedback and drops its weak
   Activity binding. It does **not** prematurely unlock an ad that the SDK still
   displays. The SDK terminal callback remains until dismissal/failure and then
   cleans up. A late callback cannot unlock a newer full-screen ad.
7. Success feedback uses the existing restorable dialog and stays pending until
   acknowledgment. Duplicate/old callback IDs cannot consume a newer operation.
   Restoring an already-successful operation presents feedback without replaying
   an ad merely because the Activity was recreated.

## Cache ownership and Activity safety

`App.provideInterstitialAdGateway()` now retains one helper **per existing
placement**. Home and Default Tones still have separate inventory; this is not a
merged shared-cache/refill/attribution refactor. The change is needed so Activity
and ViewModel destruction during every picker trip cannot discard all readiness.

Requests use application context; only `show()` receives the current valid weakly
held Activity. The SDK API explicitly accepts Activity or application context for
loading and requires an Activity for display.
[Google InterstitialAd API](https://developers.google.com/admob/android/reference/com/google/android/gms/ads/interstitial/InterstitialAd).

The repository has no added third-party mediation-adapter dependencies. Adapter
compatibility must be rechecked if mediation SDKs are introduced; no adapters,
production IDs, dashboard settings or SDK versions were changed here. Actual GMA
behavior through recreation still needs device verification.

The application-context load, cached inventory and in-flight request can survive
a destroyed host. Updating the weak display host does not restart the same pending
request. An old request has no operation-to-show continuation. Eligibility changes
are checked again on load completion and before every load/show. Consumed ads are
never cached again, and the existing 55-minute expiry remains. Process death starts
a new App/cache; no SDK object is serialized or reused across processes.

## Saved state and interrupted operations

Both ViewModel factories obtain a real `SavedStateHandle` from owner creation
extras; production construction requires it. Tests explicitly create test handles.

- Default Tones persists system/file/legacy-permission target kind and tone type
  before launching. Launcher registration order is unchanged. Results consume
  identity once and apply the correct restored type.
- A validated selection waiting for WRITE_SETTINGS preserves type, URI and exact
  imported-file cleanup ownership. Return/cancellation remains idempotent.
- If a saved write was interrupted, Default Tones compares the current OS tone to
  the requested value before recovering success. It does not blindly replay a
  write or display an ad during recovery. Uncertain validation/import work fails
  gracefully instead of claiming success or importing twice.
- Home persists group/source IDs, not contact payloads. A new ViewModel resolves
  current repository membership, reloads when needed and rejects a source mismatch
  or missing group. A saved interrupted group write reports recovery failure rather
  than asserting every contact was changed or automatically replaying a partial
  operation.
- Completed results and acknowledgment are saved as primitive IDs/flags. A shared
  small `RingtoneCompletionStore` avoids duplicating this protocol between screens.

Saved state is Android's lifecycle snapshot, not an always-durable transaction
journal. It does not guarantee recovery after force-stop, an unsaved kill or lost
external URI grants. Configuration recreation, Activity destruction with saved
state, and genuine process death are different cases; actual registry/OS behavior
must be tested separately. Restoring uncertain work never fabricates success.

## Files changed by this P0 task

Production:

- `App.kt`: retain application-owned, per-placement helpers.
- `customviews/ui/ads/InterstitialAdPolicy.kt`: remove display-frequency state;
  preserve backoff/full-screen exclusion.
- `customviews/ui/ads/AdLoadingHelper.kt`, `InterstitialAdGateway.kt`: application
  request context, weak host attachment/detachment, cache-only operation show,
  exactly-once client completion and SDK full-screen cleanup.
- `ui/RingtoneCompletion.kt` (new): shared saved completion/acknowledgment protocol.
- Home ViewModel/factory/state/screen: saved group identity, current membership,
  independent assignment success, lifecycle-owned one-shot ad opportunity and
  restorable feedback; preload when choosing a source.
- Default Tones ViewModel/factory/state/screen: saved picker/write/completion state,
  interrupted-work recovery, result consumption and lifecycle-owned presentation.
- `DefaultToneCompletionDialog.kt`: reuse the existing dialog with the common
  completion model. Existing theme, resources and user-dismissal protocol remain.

Tests:

- `InterstitialAdPolicyTest`, `AdRuntimeRegressionTest`,
  `DeviceDefaultTonesViewModelTest`, `HomeViewModelFlowTest` updated.
- New `DefaultToneRestorationTest`: recreated model/handle state for all three
  system and file targets, cancellation, legacy permission, WRITE_SETTINGS,
  interrupted writes/imports and no false success.
- New `P0RingtoneLifecycleUiTest`: real external picker/UI and Activity Result
  routing with the developer option OFF and ON; repeated group assignments,
  ringtone/notification/alarm, file selection and cancellation. Developer setting
  is restored in `finally`; changing it is guarded to disposable emulators.
- `testing/AudioFixtures.kt` (new) shares the identical existing synthetic WAV
  bytes with the MediaStore contract test and new real-file-picker test. Only the
  test fixture was extracted; production MediaStore/import code is unchanged.

## Verification status

Final successful command:

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest :app:lintDebug
```

The final command completed successfully in 29 seconds. Earlier wrapper-cache
or local-socket permission failures were environmental startup failures, not
successful test execution. Supplemental cached compiler/JUnit results were kept
separate from the normal Gradle checks.

| Check | Final result |
| --- | --- |
| `:app:testDebugUnitTest` | PASS: 238 tests, zero failures/errors/skips in the final normal Gradle run. |
| `:app:compileDebugKotlin` | PASS on the final application-scoped-cache implementation. |
| `:app:compileDebugAndroidTestKotlin` | PASS, including real external-picker/developer-option tests. Compilation is not execution. |
| `:app:lintDebug` | PASS: 66 warnings, no errors. New test-only-constructor warnings were removed by requiring owner-provided saved state in production. |
| `git diff --check` | PASS; rechecked when packaging. |
| Supplemental compiler/JUnit | Used while Gradle was blocked. An earlier suite passed 238 tests. After changing App ownership, the partial recompilation harness failed five Banner cases with Kotlin module-name `NoSuchMethodError` against stale App/Banner binaries. Those are not claimed passed by that harness; the authoritative full Gradle rebuild subsequently compiled everything and passed all 238 tests, including Banner cases. |
| Connected Android tests | UNVERIFIED: this session's attempts were blocked before execution by Gradle wrapper-cache permissions; direct ADB also failed to create/access its local socket. No real picker, developer-option, window or live-GMA pass is claimed. |

No tests were disabled/skipped to mask failure. Tests use synthetic data and test
ad loading shadows/gateways. JVM state copying is not proof of Android SavedState
registry delivery, real OS default-tone writes, SDK windows, paid events or revenue.
The instrumentation gateway tests verify UI routing to a deterministic tone manager,
not actual GMA impressions or production contact-provider writes.

## Required device/manual release matrix — all UNVERIFIED here

Use a disposable, compatible debug QA installation with test ad IDs. Preserve the
original developer-option setting and restore it after each matrix. Do not resolve
signature mismatches by deleting a personal installation/data without authorization.

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.milen.grounpringtonesetter.ui.P0RingtoneLifecycleUiTest,com.milen.grounpringtonesetter.ui.DefaultToneCompletionLifecycleTest,com.milen.grounpringtonesetter.ui.DefaultTonesAndBackupUiTest,com.milen.grounpringtonesetter.ui.HomeFunctionalityUiTest#searchClearAndActivityRecreationRestoreTheCompleteGroupList,com.milen.grounpringtonesetter.ui.home.GroupCountLayoutTest'
```

1. With Don't keep activities OFF, then ON, select each default ringtone,
   notification and alarm through the real Android picker. Confirm the selected
   value in Android sound settings and hear/inspect the correct type; app feedback
   alone is not OS-write proof. Repeat file selection where supported, cancel both
   picker types, and repeat operations. Expect one assignment/feedback per result.
2. Assign group tones to a QA group six or more times, including the immediate
   second operation. Verify actual contact ringtone values. Repeat after recreation
   and with an unavailable ad. Ads must never turn assignment success into failure.
3. Verify debug GMA request/load/show/impression/dismiss/next-preload diagnostics.
   Test a ready ad, loading ad, no-fill/backoff/recovery, failed display, consent
   unavailable and owned/pending/unknown entitlement. No artificial wait or count
   cap should suppress a new eligible operation; no-fill is not a promised impression.
4. Recreate before load completes, with a ready application cache, during an
   external picker, and around ad display/dismissal. Confirm the current host is
   used, consumed inventory is not reused, and navigation never produces an old
   operation's delayed ad on another destination.
5. Separately exercise genuine process death while the external picker is open,
   then return. Test interrupted WRITE_SETTINGS/import/write recovery. Observe the
   conservative recovery behavior above; no duplicate work or unproven success.
6. Rerun `GroupCountLayoutTest` and the earlier search/recreation failure. The
   contact-count XML, its vertical-centering fix, sizing and accessibility were
   not changed by this P0 task. Previous window-focus/install/device failures are
   not resolved by compilation and remain unverified.

The new external-picker automation assumes stock Android picker IDs and installed
sample tones; a different OEM UI must be adapted or verified manually. An absent
picker/file row is a test failure, not a silent pass or skipped test. The file
fixture is removed by its exact owned URI. Live GMA/UMP and actual OS/provider
writes need the separate QA app/manual checks described above.

## Preservation, Git state and review package

A pre-task source snapshot was captured before edits. Hash checks confirm that
`app/build.gradle.kts` (including its pre-existing 8.7.4 version change), contact
badge source/layout/alignment, adapter changes and Banner source remain unchanged
by this task. Purchase, entitlement, UMP, SDK initialization, navigation graph,
permissions and dependency versions were not modified. Prior review files remain
historical; their old 90-second/four-impression requirements are superseded by this
P0 request. No new translated strings or sensitive logging were introduced.

The tree already contained staged additions. No staging/index mutation command
was run. During execution a staged entry for new `ui/RingtoneCompletion.kt` appeared
outside these read-only Git operations. Original staged sections remain unchanged.
That entry was preserved rather than reset/unstaged; therefore claiming the entire
tree is unstaged would be inaccurate. See `git-state.txt` for exact current state.

`full-review.patch` includes staged **and** unstaged source changes against HEAD,
plus untracked source/test/review text. It excludes secrets, generated caches,
binaries and all patch artifacts themselves. `task-changes.patch` is the source
change set relative to the pre-task snapshot, so reviewers can separate P0 work
from earlier AdMob/contact-count work. `git-state.txt` contains requested status
and diff statistics. No staging was used to generate either patch.

No revenue, eCPM or Match Rate uplift is claimed. Application-context cache reuse,
real Activity Result restoration, SDK callback ordering and the developer-option
matrix require device validation before clearing these P0 release blockers.
