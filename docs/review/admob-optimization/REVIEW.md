# AdMob optimization — local independent review

Date: 2026-11-23. Scope: CODEX TASK 1.
Baseline: `63c029ad1372679f246e1cc705d816f513178662`. All changes remain local and uncommitted.
No index writes, commits, pushes, merges, rebases, resets, branches, tags, releases,
publication, deployment or dashboard changes were performed.

## Confirmed causes and evidence

| Finding | Repository evidence / regression signal | Change |
| --- | --- | --- |
| Banner backoff could be bypassed | `setAdsEnabled(true)` reenters `maybeLoadBanner()` after failure; the old guard omitted `pendingRetry`. Regression test repeatedly enables during backoff and checks that the request count stays at one. | Guard pending retry; retain one cancellable layout callback; reject callbacks from obsolete SDK views and cancelled retry runnables. |
| Preloading ignored the session cap | `canLoad()` previously checked only backoff. The dismissal handler preloaded after impression four. | Include the four-impression cap in load eligibility, without using show eligibility or suppressing preload during cooldown. |
| Home initial preloading was ineffective | `onHomeResumed()` read `_state.value.entitlement`, which stays UNKNOWN; billing is added to the public combined state. Readiness arriving after resume also had no preload observer. | Observe combined entitlement/readiness while the view is RESUMED, deduplicate Boolean eligibility through existing `collectStateIn`, and recheck authoritative `billing.state` in `onAdsReady()`. |
| Expired caches could be reported as ready | `loadInterstitialAd()` returned true for any non-null cache before checking readiness or age; expiration was checked only on the show path. | Check eligibility and expiration before cache reuse. Expire at age >= 55 minutes, including timestamp zero. |
| Automatic reloads and delayed callbacks lacked entitlement checks | The helper checked readiness, while entitlement was checked only by callers. A purchase during display/loading could therefore permit later requests. | Check current NOT_OWNED entitlement inside load/show and after loading. Eligibility loss resolves a waiting show as SKIPPED, preserving the normal operation completion path. |

These are code-path findings, supported by deterministic tests, not measurements
of production no-fill or country-specific revenue loss.

Additional defensive fixes: terminal guards ignore obsolete/duplicate load and
show completions; synchronous load exceptions release loading state and apply
existing backoff; displayed ads are consumed before SDK show; dismissal and show
failure release the global full-screen lock and clear the display callback.
The policy now also enforces cooldown when the first impression timestamp is zero.
Default Tones completion uses weak references and rejects a destroyed originating
view rather than retaining the Fragment through a delayed ad completion.
No actual duplicate SDK callback, memory leak or SDK exception was observed in
production; tests exercise those defensive paths synthetically.

## Hypotheses and observable signals

| Investigation hypothesis | One observable signal | Outcome |
| --- | --- | --- |
| Repeated enable calls defeat banner delay | A second request before the first 30-second timer expires | Confirmed reachable in previous code; protected by a regression test. |
| Session-capped users trigger useless preload | Request after the fourth impression is dismissed | Confirmed previous control flow; regression asserts zero replacement requests. |
| Late readiness misses Home preload | No preload when eligibility becomes true after resume | Missing observer and incorrect base-state entitlement confirmed; lifecycle observation added. On-device lifecycle journey remains pending. |
| Preload reuses expired inventory | Successful cache return at age 55 minutes | Confirmed missing load-path expiration; load/show expiry regression tests added. |
| Two placement helpers waste meaningful inventory | Both helpers successfully load but only one ad is consumed before navigation/expiration | Two caches confirmed, but frequency and financial significance are unmeasured. Shared cache deferred. |

## Changed files and methods

All production paths below are under `app/src/main/java/com/milen/grounpringtonesetter/`.

| File | Methods / behavior |
| --- | --- |
| `customviews/ui/ads/AdBannerView.kt` | `maybeLoadBanner`, `destroyBanner`, retry scheduling, view replacement and SDK callbacks: pending-backoff guard, cancellation, identity checks, authoritative eligibility. Adaptive size calculation unchanged. |
| `customviews/ui/ads/InterstitialAdPolicy.kt` | `canLoad`, `canShow`, new `isSessionCapReached` / `showBlockReason`: cap-aware preload, unchanged cooldown/backoff, debug skip reasons. |
| `customviews/ui/ads/AdLoadingHelper.kt` | `loadInterstitialAd`, `showInterstitialAd`, expiration/cache cleanup, completion helpers: eligibility, stale callback protection, request deduplication, cache consumption and terminal cleanup. |
| `ui/home/HomeScreen.kt` | `onViewCreated`: RESUMED, view-lifecycle-aware combined readiness/entitlement observer. |
| `ui/home/viewmodel/HomeViewModel.kt` | `onHomeResumed` and `onAdsReady`: move preload into readiness path and read current billing entitlement. Purchase guard behavior is unchanged. |
| `ui/defaulttones/DeviceDefaultTonesScreen.kt` | `onViewCreated` / `onResume`: consolidate redundant preload triggers into one RESUMED observer; weak completion references and originating-view destruction guard. Existing ad-then-success-dialog ordering remains. |

`AdsManager`, UMP, Billing implementation, contact/ringtone operations, backup,
permissions, production IDs, dependencies and build configuration are unchanged.
There are no new user-facing strings or placements. Existing SDK-load Activity
context and the helper's WeakReference Activity ownership are preserved.

## Tests and actual verification

Changed `InterstitialAdPolicyTest.kt`: added five tests (eight total), covering
preload during cooldown, cap enforcement after success/reset, global full-screen
locking, capped failure backoff and timestamp-zero cooldown. Existing show/cap
and load-success reset tests remain.

New `AdRuntimeRegressionTest.kt`: 17 Robolectric tests, with SDK loads intercepted
by test-only shadows. Coverage includes banner 30/60/120/120-second retries, repeated
enable emissions, success reset, disable/detach cancellation, stale SDK/runnable
callbacks, eligible entitlement/readiness, delayed eligibility loss, request
coalescing and backoff, stale load callbacks, load/show expiration, fourth-impression
dismissal, failed/throwing display, throwing load, cross-helper full-screen exclusion,
Activity replacement/destruction and Home's authoritative entitlement check.
No live ads or production services are used by these tests.

| Command / check | Actual result |
| --- | --- |
| `./gradlew :app:testDebugUnitTest --tests '*InterstitialAdPolicyTest'` | Blocked before test execution: normal Gradle wrapper cache lock is outside writable roots. |
| `./gradlew :app:testDebugUnitTest` | PASS on the final normal-environment run: 230 tests, zero failures/errors/skips, including all 25 ad regressions. |
| `./gradlew :app:compileDebugKotlin` | PASS (also compiled during the full unit test run). Final explicit invocation was combined with lint. |
| `./gradlew :app:lintDebug` | PASS. SARIF reports 66 warnings and no errors; none point to the changed ad/Home/Default Tones files or new ad test. Existing AGP/Kotlin deprecation warnings remain. |
| Isolated installed Gradle, `GRADLE_USER_HOME=/private/tmp/grs-admob-gradle`, `--offline --no-daemon :app:testDebugUnitTest :app:compileDebugKotlin :app:lintDebug` | Blocked before build startup: `FileLockContentionHandler` cannot create a socket, `java.net.SocketException: Operation not permitted`. |
| Supplemental cached Kotlin 2.4.10 compiler | PASS: changed production sources, ad sources, test doubles and ad tests compiled against local Android/dependency jars and existing debug output. This is not a clean Gradle build or dependency-resolution validation. Warnings: unchanged adaptive-size API and test Resources constructor are deprecated. |
| Supplemental JUnit 4.13.2 / Robolectric 4.15, API 34 | PASS: `OK (25 tests)` across `InterstitialAdPolicyTest` and `AdRuntimeRegressionTest` (8 + 17). Cached GMA API 25.3.0 and coroutines 1.11.0 match project configuration. |
| `adb devices` | Initially blocked by local socket permissions; later found emulator-5554, API 35 (`Gate3_API_35_Pixel9`). Device access subsequently became unreliable. |
| Selected `:app:connectedDebugAndroidTest` run (HomeFunctionalityUiTest + DefaultTonesAndBackupUiTest) | Built APKs and started 19 tests. First reported result: `searchClearAndActivityRecreationRestoreTheCompleteGroupList` FAILED with Espresso `RootViewWithoutFocusException` (no window focus for 10 seconds). Progress then stalled at 1/19; stopped this attempt with Ctrl-C (exit 130). The other 18 tests are unverified, not passed. |
| Isolated recreation-test rerun | FAILED before executing the test: ddmlib property fetch timed out; device skipped with Unknown API Level, followed by No compatible devices connected. This did not establish whether the recreation failure reproduces. |
| `git diff --check` | PASS after implementation. |

Initial restricted-environment attempts failed before Gradle/ADB could run. A later
normal-environment retry became available and the full Gradle unit/compile/lint
checks succeeded. A selected Android run was attempted but could not complete; its
window-focus failure remains unresolved, and the isolated retry was blocked by
device property-fetch failure. The supplemental results above are retained as separate evidence;
they are not counted as additional distinct tests and do not substitute for Gradle.
Initial targeted tests were written before the policy fix, but that Gradle red run
was blocked. No successful red run is claimed.

Reproduce selected Android checks (no code or configuration changes required):

```bash
./gradlew :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.milen.grounpringtonesetter.ui.HomeFunctionalityUiTest,com.milen.grounpringtonesetter.ui.DefaultTonesAndBackupUiTest'
./gradlew :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.milen.grounpringtonesetter.ui.HomeFunctionalityUiTest#searchClearAndActivityRecreationRestoreTheCompleteGroupList'
```

### Remaining verification matrix

| Scenario | Evidence / remaining check |
| --- | --- |
| Consent pending / allowed / denied or restricted | Readiness false/true gates tested; actual UMP forms and geographic states require device testing. No UMP logic changed. |
| Remove Ads owned / pending / unknown / not owned | All entitlement gates and purchase-while-loading tested. Real purchase/restore still requires existing release smoke checks. |
| Banner backoff, cancellation, success reset, stale callbacks | Deterministic Robolectric coverage; visually verify adaptive banners, hiding and reattachment on device. |
| Interstitial preload, cap, cooldown, failure backoff | Policy and helper regressions pass; preload remains allowed during cooldown. |
| Failure, dismissal and exactly-once completion | Synthetic callback/throw tests pass, including obsolete terminal callbacks. Real SDK presentation needs smoke testing. |
| Background/foreground and navigation | RESUMED observers audited and compiled; request deduplication tested. Full Fragment background/navigation journeys were attempted but not completed. |
| Recreation / destroyed Activity | Replacement Activity and delayed completion rejection pass in Robolectric. The device recreation test failed on window focus; isolated rerun could not access device properties. Full device/process recreation remains unverified. |
| Cache expiration | Load and show paths tested at the 55-minute boundary. |
| Ringtone completion while ads unavailable | Eligibility-loss show result is tested as SKIPPED; existing Home and Default Tones success routes inspected. The selected ringtone/default-tone UI suite did not complete, so these device operations are not claimed verified. |

## Diagnostics

Existing `AdDiagnostics` / `Tracker` infrastructure is reused. Request, load
success/failure, impression, click, show failure and paid-event tracking remain.
New debug-only messages distinguish cache reuse, cap-blocked loads, discarded
late loads, readiness/entitlement skips, cooldown/cap/full-screen show skips,
show attempt, dismissal and reaching the session cap. Existing expiration logs
remain. All added routine messages pass through the existing BuildConfig.DEBUG
gate; no duplicate analytics or personal data logging was added. Existing
`CODEX_DEBUG` prefix is retained behind that debug gate as requested.

## Expected benefit and measurement

Expected: fewer premature banner requests during no-fill/network failures, zero
new Interstitial loads once the process cap has been reached, fresh cache reuse,
and improved readiness at existing legitimate Home completion opportunities.
No revenue percentage or Match Rate improvement can be claimed from local tests.
The 90-second cooldown, maximum four impressions per process, existing placements
and all display triggers remain intact. Readiness resolution only preloads.

After separate review and any independently authorized rollout, compare matched
14–28-day periods by app version, country and format. Track requests per active
user, matched requests, impressions, show rate, eCPM, total revenue and revenue per
active user together. Keep floor settings and placements stable. Check existing
`ad_paid_event` coverage and debug request-to-dismiss traces on test devices.
Interpret an increase in legitimate Home preloads separately from eliminated
wasted requests. Low country Show Rate and the reported ~45% Match Rate may still
reflect demand, user navigation, eligibility or traffic mix. No dashboard access
or configuration changes were made or assumed necessary.

## Shared Interstitial cache assessment — deferred

Home owns a helper through its ViewModel; Default Tones creates a helper for its
view. Both use the same production unit and app-process display policy, so two
loaded ads can coexist. A request started on a screen that is then left cannot
be cancelled through the current SDK API. Actual waste depends on navigation,
consumption and expiration; neither production counts nor a revenue impact were
established here.

Potential benefit of one cache: coalesce cross-screen loads, reuse one fresh ad
across natural breaks and avoid stranded per-screen inventory. Appropriate owner:
an application-scoped cache with request/backoff/expiration state, no strong
Activity/Fragment references, and an explicit consumption/owner token. A current
valid Activity should be supplied only for display, with the existing process
full-screen lock and frequency policy remaining authoritative.

Risks: simultaneous consumers and double show, callback transfer after navigation,
process-wide loading failure/backoff interaction, attribution to request versus
show placement, entitlement/consent changes during load, and SDK/adapter context
retention. The GMA API accepts Activity or application context for loading, but
Google notes that mediated networks can require an Activity. Preserve adapter
compatibility before changing context ownership in any future design.
[Interstitial API](https://developers.google.com/admob/android/reference/com/google/android/gms/ads/interstitial/InterstitialAd),
[mediation context guidance](https://developers.google.com/admob/android/mediation#initialize_your_ad_object_with_an_activity_instance).

Minimal alternatives implemented now: session-cap-aware loads, authoritative
eligibility checks, deduplicated lifecycle triggers, expiration before reuse and
per-helper in-flight request coalescing. A global loading lock by itself is not a
shared cache: it could starve the foreground screen while another helper owns the
loaded result. No shared-cache refactor, new mediation integration, dashboard
experiment or SDK migration was implemented. These require separate scope/review.

## Risks and review scope

- Full unit suite, compile and lint pass. Connected testing is incomplete: one recreation test reported a window-focus failure; the run was stopped after progress stalled, and the isolated retry failed device property discovery. This failure has not been attributed to or excluded as an app regression. The fixture uses owned entitlement/disabled ads, so a direct ad show is not expected, but that alone is not proof of an environmental cause. No unrelated production fix was made.
- Real UMP/Billing/live-ad journeys and the complete Android suite remain unverified. Rerun the two selected classes, especially recreation and ringtone/default-tone completion, on a stable emulator/device.
- Lifecycle collection now starts preloading only at RESUMED. Verify returning
  from consent, billing, pickers and full-screen ads, especially Fragment view
  recreation. Repeated true eligibility while already loaded/in flight reuses
  cache/coalesces requests.
- Default Tones intentionally suppresses an old success-dialog callback after its
  originating view is destroyed. Verify success-dialog behavior on live dismissal
  and rotation. Weak references avoid prolonging Fragment lifetime.
- Separate placement caches remain; process-wide request coalescing is deferred.
- No evidence ties the reported country revenue metrics to a specific defect.

Pre-existing work was preserved: `CircleWIthText.kt`, `GroupsAdapter.kt`,
`item_group_entity.xml`, `GroupCountLayoutTest.kt`, `docs/review/contact-count/QA.md`
and `.kotlin/`. These were present before Task 1 edits and are not attributed to
this implementation.

`task.patch` contains all Task 1 production/test changes, including the new
untracked regression file and this report. `working-tree.patch` additionally
contains the pre-existing tracked edits and untracked contact-count source/test
and review document. Both are generated without staging. Generated caches,
binaries and patch files themselves are excluded. `git-state.txt` records the
requested final status and diff stat; Git's normal diff stat excludes untracked
files, which are explicitly included in the patches.
