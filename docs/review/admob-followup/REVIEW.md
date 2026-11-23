# AdMob independent-review follow-up

Date: 2026-11-23. Baseline commit: `63c029ad1372679f246e1cc705d816f513178662`.
All changes remain local, unstaged and uncommitted. No staging, commit, push,
merge, reset, rebase, branch/tag creation, release, production deployment or
publication was performed. Earlier review artifacts are preserved unchanged;
this report supersedes their description of the three amended behaviors below.

## Outcome

Issues 1–3 have local fixes and focused regression coverage. The complete JVM
suite, production Kotlin compile, Android test Kotlin compile, lint and whitespace
checks pass. **Issue 4 and the Android lifecycle journeys remain unverified on a
working device.** The connected runner rejected the available emulator after ADB
property-fetch timeouts. No successful rendering or recreation result is claimed.

## Confirmed findings and fixes

### 1. Banner visibility lifecycle

Confirmed: a successful SDK callback while a parent was INVISIBLE destroyed the
banner because `isBannerRequestAllowed()` mixed consent/ownership with visibility.
The view remained attached, and no visibility-restoration callback guaranteed a
replacement request. Failure callbacks had the same visibility/ineligibility mix.

`AdBannerView` now separates:

- `isBannerEligible()`: explicitly enabled, SDK/consent ready, confirmed NOT_OWNED.
- `isBannerRequestAllowed()`: eligible, attached, visible and shown.

Load callbacks reject permanent advertising ineligibility and obsolete AdViews,
but retain a successful eligible load while temporarily hidden. A parent/self
visibility-restoration notification calls `maybeLoadBanner()`. An already-loaded
or loading banner is reused; pending backoff still blocks another request.
Failures while hidden retain the existing 30/60/120-second retry progression.
If the timer expires while hidden it makes no request; visibility restoration
resumes loading. Disable/detach cancellation and adaptive sizing are unchanged.

New passing Robolectric regressions:

1. Parent hidden during loading, successful callback, restoration: same loaded
   AdView remains and request count stays at one.
2. Failure while hidden: backoff remains scheduled, expiry does not load while
   hidden, and becoming visible starts exactly one replacement request.
3. Purchase during hidden loading: the late ad is discarded and restoration
   cannot load for the owned customer.

### 2. Default Tones completion feedback

Confirmed: the earlier callback checked the old Fragment view's lifecycle and
silently dropped the success dialog after that view was destroyed. It had no
retained pending result for a replacement view.

The NOT_OWNED post-ad success route now stores a `DefaultToneAdCompletion` in the
existing screen ViewModel. A completion ID ties the one-shot ad event and its
terminal callback to that result. The SDK callback holds only a WeakReference to
the ViewModel plus the ID; it never calls a Fragment or Activity dialog handler.
It marks that matching result ready, including when no view is observing it.
Duplicate/obsolete callbacks and obsolete dismissal results cannot consume a
newer completion or recreate a consumed result.

A view-lifecycle RESUMED observer presents ready feedback through a child
`DefaultToneCompletionDialog`. FragmentManager restores this dialog across
configuration changes, and a fixed tag prevents duplicate instances. The pending
result remains until OK or user cancellation publishes a Fragment result.

Important implementation detail verified in the installed AndroidX Fragment 1.8.9
source: `DialogFragment.onDestroyView()` can call `onDismiss()` during teardown.
Therefore acknowledgment is deliberately in the OK action and `onCancel()`, not
`onDismiss()`. Rotation/view destruction does not itself consume the pending result.
Existing resources, title, theme and success message are reused.

Navigation semantics: feedback is not shown on another destination. A retained
Default Tones back-stack entry can receive the callback while its view is absent
and show the result when the user returns. Popping/destroying the entry does not
produce an orphan dialog on Home. Process-death persistence of an unfinished ad
request is not added; the pending result is retained by the normal ViewModel
configuration-change lifetime. Callback delivery still depends on the SDK
providing a terminal result. The existing owned/unknown/pending success route and
all tone assignment/import/permission logic are unchanged.

New passing ViewModel regressions cover ready feedback retained without a view
collector, duplicate callback suppression, acknowledgment, and stale completion
and dismissal IDs. Two new Android tests compile and exercise the intended real
screen/dialog lifecycle, but their runtime execution is **unverified**:

- Deferred completion across Activity recreation, duplicate terminal callback,
  restoration of an already-visible dialog, acknowledgment, and another recreation
  without repeating the ad or success feedback.
- Completion while another destination is visible, followed by return to the
  retained Default Tones entry and one success dialog.

The instrumentation fake gateway now supports deferred completion and explicit
reset. This is test-only code; the production ad gateway contract is unchanged.

### 3. Home preload eligibility

The reviewed observer depended on combined Home UI state, which also waits on
contact/source repositories. Home now collects
`HomeViewModel.interstitialPreloadEligibility(adsManager.canLoadAds)` while its
view is RESUMED. This Flow combines `billing.state` and readiness directly and
deduplicates the resulting Boolean. It does not depend on UI state subscribers,
contact loading or group data. `onAdsReady()` still rechecks the authoritative
billing value, and the real ad helper still checks readiness/ownership itself.

The expanded passing regression checks initial ineligibility, delayed readiness,
confirmed entitlement becoming available while the public UI state is still
UNKNOWN, repeated emissions, collector cancellation in the background, replay on
return, readiness toggling and owned/pending/unknown rejection. Eligibility only
preloads; it never shows an ad. Cache/in-flight deduplication remains in the helper.

## Changed scope

Production changes in this follow-up:

| File | Amended methods / behavior |
| --- | --- |
| `customviews/ui/ads/AdBannerView.kt` | Eligibility/request gates, `onVisibilityChanged`, load callbacks and retry scheduling. |
| `ui/defaulttones/DeviceDefaultTonesState.kt` | Retained post-ad completion data and completion ID on the existing ad event. |
| `ui/defaulttones/viewmodel/DeviceDefaultTonesViewModel.kt` | Record pending success after the existing tone operation; match completion/acknowledgment by ID. |
| `ui/defaulttones/DeviceDefaultTonesScreen.kt` | Weak ViewModel callback, pending-result observer and dismissal result listener. |
| `ui/defaulttones/DefaultToneCompletionDialog.kt` (new) | Restorable success dialog with user-only acknowledgment. |
| `ui/home/viewmodel/HomeViewModel.kt`, `ui/home/HomeScreen.kt` | Direct Billing/readiness Flow and lifecycle-aware collection. |

Test changes: `AdRuntimeRegressionTest.kt`, `DeviceDefaultTonesViewModelTest.kt`,
new `DefaultToneCompletionLifecycleTest.kt`, test-only
`RegressionTestApplication.kt` and `UiTestFixtures.kt`.

Earlier Interstitial cache/cap/backoff safeguards remain intact. No changes were
made to the 90-second cooldown, four-impression process cap, ad display triggers,
production IDs, placements, SDK/library/build versions, UMP or Billing behavior.
No new user-facing strings or sensitive logging were added. The contact-count
implementation and `GroupCountLayoutTest` were preserved unchanged.

## Actual verification

Final successful command:

```bash
./gradlew :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest :app:compileDebugKotlin :app:lintDebug
```

| Check | Result |
| --- | --- |
| Full JVM suite | PASS: 235 tests, zero failures, errors or skips. Includes 20 ad runtime tests, 8 Interstitial policy tests, and the expanded Default Tones ViewModel suite. |
| Production Kotlin compile | PASS. Existing adaptive banner API deprecation warning remains. |
| Android test Kotlin compile | PASS, including the rendering test and new completion lifecycle tests. Compilation is not device execution. |
| Lint | PASS: 66 warnings, no errors. No SDK/dependency updates were made to address existing warnings. |
| `git diff --check` | PASS. |
| Initial targeted Gradle attempts | Blocked by sandbox access to the wrapper-cache lock; later normal invocations completed the checks above. No red-test success is claimed from the blocked attempts. |
| Connected test attempt | FAILED at device discovery: ddmlib `PropertyFetcher` timed out, device skipped with `Unknown API Level`, then `No compatible devices connected`. No selected application test executed successfully in this follow-up. |
| Isolated temporary emulator recovery | A configuration was created only under `/private/tmp` using the already-installed API 35 image. The emulator backend exited with code 1 before boot, including on a verbose retry. Existing AVDs and SDK versions were not changed. No stable replacement device was obtained. |

An initial compile error in the newly added Android test was corrected; final
Android test compilation passes. The earlier review's recreation-test
`RootViewWithoutFocusException` remains unresolved: this follow-up could not run
the test far enough to reproduce or exclude it as an app regression.

## Device checks still required — not passed

The attempted selection includes:

```bash
./gradlew :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.milen.grounpringtonesetter.ui.home.GroupCountLayoutTest,com.milen.grounpringtonesetter.ui.HomeFunctionalityUiTest#searchClearAndActivityRecreationRestoreTheCompleteGroupList,com.milen.grounpringtonesetter.ui.DefaultTonesAndBackupUiTest,com.milen.grounpringtonesetter.ui.DefaultToneCompletionLifecycleTest'
```

This represents 13 selected application test methods; the harness's synthetic
no-compatible-device failure is not a rendering or application-test result.

`GroupCountLayoutTest` defines counts including 9, 90, 100, 999 and 1000, 200%
font scale, 288dp card width (a 320dp screen after margins), other widths/scales,
480/600dpi, three themes, wide/narrow rebinding, measurement/drawing and scrolling.
Those assertions were **not executed successfully here**. There is no final
Pixel 10a or emulator rendering proof to claim.

Remaining unverified scenarios include real Fragment/dialog restoration,
recreation during a live GMA load/display, background/foreground feedback,
navigation/return, the previously interrupted search/recreation test, and relevant
Default Tones/Backup UI operations. JVM state tests and compiled Android tests do
not replace these checks. A stable emulator/device run is needed before approval.

## Review materials and preservation

`full-review.patch` is the complete current textual working-tree diff against the
baseline, including new/untracked production and test sources, existing contact
count changes and review Markdown/text documents. It is generated without staging.
Generated caches, binaries and patch files themselves are excluded. Historical
`admob-optimization` and `contact-count` notes are included as preserved history;
this follow-up report is the current status. Earlier patch files remain unchanged
on disk and are not recursively embedded.

`git-state.txt` records the requested final Git status and diff stat. Normal Git
stat excludes untracked files; those files are explicitly present in the patch.
All work stops for independent review after this report. The shared Interstitial
cache proposal remains deferred; no revenue lift or device verification is claimed.
