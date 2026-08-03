# Regression Test Matrix

This document maps the app's atomic capabilities to automated protection. Test names describe
behavior and repository state is asserted wherever the feature changes data.

| Capability | JVM characterization | Deterministic UI | Android contract |
| --- | --- | --- | --- |
| First launch and contacts permissions | `HomeViewModelFlowTest` | `DeterministicFeatureJourneyTest` | `ContactsProviderContractTest` |
| Contact-source selection and stale/empty sources | `CreateGroupSourceResolutionTest`, `HomeViewModelFlowTest` | Seeded on-device source in the test application | `ContactsProviderContractTest` |
| Offline/reconnect navigation | `HomeViewModelFlowTest`, `GuardedNavigationPolicyTest` | `NoInternetNavigationTest` | Navigation graph on each API |
| Home empty state, search, sorting, themes | Home presentation and preferences tests | Create/search journey | Locale rendering smoke |
| Group create and rename | `PickerViewModelFlowTest` | Create/search/rename journey | Contacts provider read contract |
| Group member changes and blocked reassignment | `PickerViewModelFlowTest`, picker selection tests | Fake repository state assertions | Contacts provider read contract |
| Group delete and protected groups | `HomeViewModelFlowTest`, deletion capability tests | Fake repository supports exact group removal | Contacts provider cleanup uses exact IDs |
| Group ringtone selection and ads result | Ringtone selection, format, and interstitial policy tests | Fake interstitial gateway | `MediaStoreAudioContractTest` plus filename and permission policies |
| Reset all ringtones | `HomeViewModelFlowTest` | Fake repository state | Contacts gateway contract |
| Device ringtone/notification/alarm | `DeviceDefaultTonesViewModelTest` | Default-tone screen with fake manager | API-specific manager remains a release smoke item |
| Backup lock, export, preview, restore, permission | `BackupRestoreViewModelFlowTest`, archive and planner tests | Owned backup screen | Archive streams use real JVM ZIP streams |
| Billing and ads | Billing message/diagnostic and ad policy tests | No-network billing/ad gateways | Google services remain release smoke items |
| Local labels, mirror, preferences | Local store/recovery/mirror and preference tests | Fake repository journey | Encrypted store covered by Android startup |
| Accessibility and localization | Accessibility text tests and resource parity | Action controls asserted through resources | Ten-locale rendering smoke |

## Commands

- Fast JVM suite: `./gradlew :app:testDebugUnitTest`
- Core coverage report: `./gradlew :app:jacocoTestReportDebug`
- Local quality gate: `./gradlew :app:localRegressionGate`
- Complete UI and Android contract suite: `./gradlew :app:uiTestSuite`
- Underlying connected Android task: `./gradlew :app:connectedDebugAndroidTest`

The pull-request workflow runs the complete Android suite on API 27, 35, and 36. Google Play
Billing and AdMob are intentionally replaced by deterministic gateways in automation.
