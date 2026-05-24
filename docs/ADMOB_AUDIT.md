# AdMob / Google Mobile Ads Audit

Date: 2026-05-24

## Current implementation summary

- UI stack: Fragments + XML + ViewBinding. No Jetpack Compose ad integration is present.
- Detected ad formats in code: banner and interstitial.
- Not detected: rewarded, rewarded interstitial, app open, native.
- SDKs after this audit:
  - Google Mobile Ads SDK Android: `24.9.0`
  - Google UMP SDK: `4.0.0`
- Startup flow after this audit:
  - `App` initializes Google Mobile Ads once at process startup.
  - `MainActivity` requests consent once per process via UMP and exposes the privacy-options entry point when required.
  - Banner and interstitial requests are blocked until consent allows ad requests.
  - Debug and release ad configuration are separated by source set and manifest placeholder.

## Detected ad formats

| Format | Present | Notes |
| --- | --- | --- |
| Banner | Yes | Home, picker, and default-tones screens |
| Interstitial | Yes | Ringtone/default-tone completion flows |
| Rewarded | No | Not implemented |
| Rewarded interstitial | No | Not implemented |
| Native | No | Not implemented |
| App open | No | Not implemented |

## Files inspected

- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/milen/grounpringtonesetter/App.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/MainActivity.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/customviews/ui/ads/AdBannerView.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/customviews/ui/ads/AdLoadingHelper.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/utils/AdBannerViewExtensions.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/utils/Tracker.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/utils/Logger.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/ui/home/HomeScreen.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/ui/home/viewmodel/HomeViewModel.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/ui/home/viewmodel/HomeViewModelFactory.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/ui/picker/PickerScreen.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/ui/defaulttones/DeviceDefaultTonesScreen.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/ui/defaulttones/viewmodel/DeviceDefaultTonesViewModel.kt`
- `app/src/main/res/layout/fragment_home_screen.xml`
- `app/src/main/res/layout/fragment_picker_screen.xml`
- `app/src/main/res/layout/fragment_device_default_tones.xml`
- `app/src/main/res/layout/dialog_info.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/none_translateble_strings.xml`
- `app/src/main/res/xml/gma_ad_services_config.xml`
- `app/proguard-rules.pro`

## Problems found, ordered by severity

| Severity | Problem | Exact impact | Recommended fix | Implemented |
| --- | --- | --- | --- | --- |
| Critical | No UMP consent flow and no consent gate before ad requests | Banner and interstitial requests could start before GDPR/EEA consent was resolved, which is a policy and privacy risk | Add UMP, request consent at startup, gate all ad requests on `canRequestAds`, and expose privacy options when required | Yes |
| High | Debug and release ad config were mixed in shared resources and the manifest always used the production AdMob app ID | Local development could still identify the app as production, which increases QA risk and muddies separation between test and production config | Split ad unit IDs by `src/debug` and `src/release`, and use a build-type manifest placeholder for the AdMob app ID | Yes |
| High | Banner views loaded immediately in `init`, even while hidden or before entitlement/privacy state was known | Wasted requests for owned users, startup requests before ads were allowed, and lower request quality | Make banner loading explicit, only enable it when entitlement is `NOT_OWNED` and consent allows requests, and destroy it when disabled | Yes |
| High | Banners used fixed `AdSize.BANNER` | Lower monetization quality on modern devices and less responsive layouts | Use anchored adaptive banners | Yes |
| High | Interstitials were only loaded on demand at show time and had no throttle/backoff | Lower show rate, more user-visible load misses, and repeated requests after failures | Preload at natural screen points, add frequency capping, and add exponential load backoff | Yes |
| High | Home flow could show interstitials while entitlement was `UNKNOWN` or `PENDING` | Paid or pending users could still be exposed to ad flows during billing resolution | Treat unknown/pending states as no-ad paths until ownership is known | Yes |
| Medium | No paid-event tracking | Revenue events were not available in existing analytics plumbing | Attach `OnPaidEventListener` and send value/currency/precision/response data through `Tracker` | Yes |
| Medium | Ad load/show diagnostics were too shallow | Harder to debug no-fill, low show rate, and response-level failures | Add structured debug logging for load/show/impression/click paths and include response metadata | Yes |
| Medium | No visible privacy-options entry point | Users could not change privacy choices later when a privacy options form is required | Add a privacy choices action in the existing info dialog and only show it when required by UMP | Yes |
| Low | No remote config / feature flags for ad decisions | Frequency caps and ad toggles require an app release to adjust | Add a remote-config layer only if the project later adopts one | No |

## Implemented changes

- Upgraded `play-services-ads` from `24.5.0` to `24.9.0`.
- Added UMP `4.0.0`.
- Added `AdsManager` to centralize:
  - one-time Mobile Ads initialization
  - consent updates
  - privacy-options availability
  - debug-only Ad Inspector opening
- Moved SDK initialization from `MainActivity` to `App`.
- Added a consent request in `MainActivity`.
- Added a privacy-options action to the existing info dialog.
- Split banner and interstitial ad unit IDs into:
  - `app/src/debug/res/values/admob.xml`
  - `app/src/release/res/values/admob.xml`
- Split the AdMob app ID by build type via `manifestPlaceholders`.
- Reworked `AdBannerView` to:
  - use adaptive size
  - load only when enabled and attached
  - avoid duplicate loads
  - destroy cleanly when hidden/disabled
  - log debug lifecycle events
  - emit paid events
- Reworked `AdLoadingHelper` to:
  - gate requests on consent
  - preload interstitials
  - apply backoff after load failures
  - cap full-screen frequency
  - clear references after show/fail/dismiss
  - log detailed debug diagnostics
  - emit paid events
- Updated screen logic so banners only render when:
  - entitlement is `NOT_OWNED`
  - consent allows ad requests
- Updated the home flow so `UNKNOWN` and `PENDING` entitlement states do not trigger interstitial display.
- Added `InterstitialAdPolicyTest` to cover cooldown, session cap, and backoff behavior.

## Remaining manual AdMob console checklist

- Confirm `app-ads.txt` is published on the production domain and verified in AdMob.
- Confirm the app entry is approved and fully ready in AdMob.
- Review the Policy Center for active issues.
- Verify the release package name and store listing match the AdMob app entry.
- Verify real release ad unit IDs map to the correct formats:
  - banner: `ca-app-pub-6177746105485183/9034496655`
  - interstitial: `ca-app-pub-6177746105485183/3535585593`
- Review country, ad unit, and ad format reports separately.
- Interpret match rate, show rate, impressions, eCPM, and revenue together instead of in isolation.
- Avoid overly aggressive eCPM floors unless reporting supports them.
- Prefer Google-optimized floors / all prices if manual floors are underperforming.
- Keep blocking controls narrow enough that inventory is not unintentionally throttled.
- Consider mediation only if traffic volume justifies the added operational overhead.
- Register QA test devices in AdMob/UMP debug workflows when testing on physical devices.
- Ensure the Privacy & messaging tab has:
  - a consent message configured if your traffic requires it
  - a privacy options message configured if legally required

## Debugging checklist for match rate / no-fill / show rate

- Confirm debug builds use:
  - test app ID
  - test banner/interstitial unit IDs
- In debug builds, long-press the version text in the info dialog to open Ad Inspector.
- Check `CODEX_DEBUG` logs for:
  - consent update results
  - banner/interstitial request starts
  - load failures with code/domain/message
  - response ID and mediation adapter
  - frequency-cap skips
- If match rate is low:
  - verify consent was granted or not required
  - verify Policy Center is clean
  - verify blocking controls and floors are not too restrictive
  - compare by country and format in AdMob reports
- If show rate is low:
  - verify the banner is actually visible on screen
  - verify entitlement is `NOT_OWNED`
  - verify interstitials are preloaded before the natural break
  - verify frequency caps are not suppressing expected shows
- If you see no-fill:
  - inspect error code/domain/message in `CODEX_DEBUG`
  - use the response ID in Ad Inspector / support workflows
  - confirm the ad unit has recent traffic and an approved app association
- If physical-device QA is needed for consent:
  - add device-level UMP debug settings temporarily
  - remove or guard them before release

## Follow-up TODOs

- If product needs runtime tuning, add a lightweight remote-config layer for:
  - banner enable/disable
  - interstitial cooldown seconds
  - max interstitials per session
- Decide whether the privacy-options action should be promoted from the info dialog to a more obvious settings surface.
- Consider switching interstitial placement decisions to a dedicated ad policy object if more full-screen placements are added later.
- If banner revenue becomes meaningful, consider screen-specific experiments in the AdMob console before changing code strategy again.
- If rewarded ads are ever added, use a dedicated reward state model and server-side verification for anything with durable value.

## Validation

Commands run:

- `./gradlew :app:compileDebugKotlin`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`

Results:

- All commands completed successfully.
- Observed warning: AGP reports `android.defaults.buildfeatures.buildconfig=true` as deprecated. This is unrelated to the ad changes but should be cleaned up later.

## Notes

- No Compose ad integration issues were found because the app does not currently use Compose for ads or screens.
- No rewarded/native/app-open policy review was required in code because those formats are not implemented in this project today.
