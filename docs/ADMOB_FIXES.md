# AdMob Fixes

Date: 2026-05-24

## What changed

- Restored `tools:replace="android:resource"` on the Ad Services manifest property.
- Added a combined ad-readiness gate so ads only load when:
  - UMP consent allows ad requests
  - Google Mobile Ads SDK initialization has completed
- Moved interstitial policy ownership to one app-session-wide instance in `AdsManager`.
- Added interstitial cache expiration at 55 minutes.
- Added global protection against showing more than one full-screen ad at once.
- Changed the Default Tones success flow so the interstitial is attempted before the success dialog.
- Added conservative banner retry/backoff with cancellation on detach/destroy.
- Kept debug logging detailed while leaving production logging quiet.
- Extended `.gitignore` to include `*.keystore`.

## Why it was changed

- The previous setup could request ads as soon as consent allowed them, even if `MobileAds.initialize(...)` had not completed yet.
- Interstitial cooldown/session cap state was local to each `AdLoadingHelper`, which meant the cap was not truly app-wide.
- Cached interstitials never expired, which risks showing stale ads or reducing show reliability.
- Default Tones could show a success dialog and then try to show an interstitial over it.
- Banner failures logged but did not retry, which wastes visible inventory opportunities on long-lived screens.
- The repo already ignored `keystore.properties` and `*.jks`, but not `*.keystore`.

## Files modified

- `.gitignore`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/milen/grounpringtonesetter/customviews/ui/ads/AdsManager.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/customviews/ui/ads/InterstitialAdPolicy.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/customviews/ui/ads/AdLoadingHelper.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/customviews/ui/ads/AdBannerView.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/utils/AdBannerViewExtensions.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/ui/home/HomeScreen.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/ui/home/viewmodel/HomeViewModelFactory.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/ui/defaulttones/DeviceDefaultTonesState.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/ui/defaulttones/viewmodel/DeviceDefaultTonesViewModel.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/ui/defaulttones/DeviceDefaultTonesScreen.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/ui/picker/PickerScreen.kt`
- `app/src/test/java/com/milen/grounpringtonesetter/ui/defaulttones/viewmodel/DeviceDefaultTonesViewModelTest.kt`

## How ad loading is now gated

- `AdsManager` keeps:
  - `canRequestAds`
  - `isMobileAdsInitialized`
  - `canLoadAds`
- `canLoadAds` is the single gate used by banner and interstitial loading.
- `canLoadAds = canRequestAds && isMobileAdsInitialized`
- Current codebase has no separate onboarding state or app-open ad flow, so there is no extra blocked-flow gate beyond consent and SDK readiness.

## Interstitial policy behavior

- There is now one shared `InterstitialAdPolicy` per app process in `AdsManager`.
- It enforces:
  - minimum interval between impressions
  - maximum interstitials per session
  - no show while another full-screen ad is already marked as showing
  - load backoff after failures
- `AdLoadingHelper` now uses named placements:
  - `home_interstitial`
  - `default_tones_interstitial`

## Interstitial expiration behavior

- Each successful interstitial load stores `interstitialLoadedAtMs`.
- Cached interstitials expire after `55 * 60 * 1000L`.
- When a cached interstitial is expired:
  - it is cleared
  - the timestamp is reset
  - a fresh preload is attempted if `canLoadAds` allows it
  - the expired ad is not shown
- After dismiss or show failure:
  - cached ad reference is cleared
  - load timestamp is reset
  - next preload is attempted only if ads are currently allowed

## Banner retry behavior

- Banner retries are conservative and only happen while the banner is:
  - attached
  - enabled
  - visible/shown
  - allowed to load ads
- Backoff sequence:
  - 30 seconds
  - 60 seconds
  - 120 seconds
  - capped at 120 seconds for later retries
- Retry count resets on success.
- Pending retries are canceled on destroy/detach/disable.
- Multiple parallel retries are prevented.

## Default Tones ad and success-dialog behavior

- `DeviceDefaultTonesViewModel` now emits `ShowInterstitialThenInfo(R.string.everything_set)` for `NOT_OWNED`.
- `DeviceDefaultTonesScreen` handles that by attempting the interstitial first.
- After the ad helper resolves, it always shows the success dialog:
  - shown
  - skipped
  - load failed
  - show failed
- Owned, unknown, or pending entitlement states still show the success dialog directly without attempting the ad.

## Debugging commands

- `./gradlew :app:processDebugMainManifest`
- `./gradlew :app:processReleaseMainManifest`
- `./gradlew :app:assembleDebug`
- `./gradlew test`

Useful runtime checks:

- Long-press the version text in the info dialog in debug builds to open Ad Inspector.
- Search logcat for `CODEX_DEBUG`.

## Project-wide ad load/show inventory

- `AdsManager.kt`
  - `MobileAds.initialize(...)`
  - `UserMessagingPlatform.getConsentInformation(...)`
  - `requestConsentInfoUpdate(...)`
  - `loadAndShowConsentFormIfRequired(...)`
  - `showPrivacyOptionsForm(...)`
  - `openAdInspector(...)`
- `AdBannerView.kt`
  - `AdView(...)`
  - `loadAd(...)`
  - `OnPaidEventListener`
- `AdLoadingHelper.kt`
  - `InterstitialAd.load(...)`
  - `OnPaidEventListener`
  - `show(...)`
- `DeviceDefaultTonesViewModel.kt`
  - emits `ShowInterstitialThenInfo(...)`
- `DeviceDefaultTonesScreen.kt`
  - receives `ShowInterstitialThenInfo(...)`
  - preloads interstitial on resumed/eligible screen state
- `HomeViewModel.kt`
  - preloads interstitial on home resume
  - shows interstitial at ringtone-application natural break
- `HomeScreen.kt`
  - toggles banner visibility from entitlement + ad readiness
- `PickerScreen.kt`
  - toggles banner visibility from entitlement + ad readiness

Not found:

- `RewardedAd`
- `AppOpenAd`
- `NativeAd`
- Compose `AndroidView` ad wrappers

## Manual AdMob console checklist

- App approved and ready in AdMob
- Policy Center has no active issues
- `app-ads.txt` is published and verified
- GDPR / UMP message is configured
- Privacy options message is configured if the app exposes it
- Banner ad unit ID is actually a banner-format ad unit
- Interstitial ad unit ID is actually an interstitial-format ad unit
- eCPM floors are not too aggressive
- Prefer Google optimized / All prices first while traffic is still low
- Blocking controls are not overly restrictive
- Reports are checked by:
  - country
  - ad unit
  - ad format
  - match rate
  - show rate
  - eCPM
  - impressions
  - revenue
- Consider mediation only after traffic is stable and large enough to justify it

## Validation results

- `./gradlew :app:processDebugMainManifest`
  - Success
- `./gradlew :app:processReleaseMainManifest`
  - Success
- `./gradlew :app:assembleDebug`
  - Success
- `./gradlew test`
  - Success

## Notes

- The `tools:replace` attribute was kept intentionally because the app uses both Firebase and Google Mobile Ads, and the requested final manifest shape requires it. During an earlier non-incremental manifest-processing run, Gradle emitted a non-blocking warning that no conflicting declaration was present in the current merge inputs, but the merge still succeeded.
- `keystore.properties`, `*.jks`, and `*.keystore` are now ignored. `git ls-files` showed none of them are currently tracked.
