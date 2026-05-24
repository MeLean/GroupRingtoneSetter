# Accessibility Audit - Group Ringtone Setter

## Scope
- Main home screen group list (`HomeScreen`, `GroupsAdapter`, `item_group_entity.xml`, `fragment_home_screen.xml`)
- Reusable custom controls used by home actions (`CustomTextColorImageButton`, `CustomRoundedButton`, `custom_toolbar_view.xml`)
- Ad diagnostics and banner placement safety checks (`AdDiagnostics`, `AdBannerView`, `AdBannerViewExtensions`)
- Localization resources for help/explanatory text (`values* / strings.xml`)

## Issues Found

### High
1. Icon action touch targets were too small (`24dp`) for reliable low-vision and TalkBack use.
- Impact: difficult activation, missed taps, accessibility regression risk.
- Fix: increased icon action size to `48dp`; switched icon rendering to `centerInside` with padding.
- Implemented: Yes.

2. Group card meaning was ambiguous (badge number and ringtone text lacked explicit labels).
- Impact: users could not reliably infer what number/text represented; blind users had poor context.
- Fix: each card now shows labeled lines:
  - `Contacts: X`
  - `ringtone: <name or no ringtone>`
- Implemented: Yes.

3. Card-level TalkBack context was incomplete and icon-only actions lacked group-specific labels.
- Impact: ambiguous screen reader output for destructive/edit actions.
- Fix: card summary + contextual action descriptions now include group name and purpose (manage/edit/delete/choose ringtone).
- Implemented: Yes.

### Medium
4. Wrapper custom views did not reliably forward `contentDescription` to the actual clickable inner control.
- Impact: TalkBack could announce generic/default labels.
- Fix: `CustomTextColorImageButton` and `CustomRoundedButton` now forward `contentDescription` to inner widgets.
- Implemented: Yes.

5. Help explanation for card semantics was not available from home actions.
- Impact: first-time users needed trial/error to understand card metadata.
- Fix: added Home actions menu entry (`Info`) and updated localized `info_text` with explicit card explanation.
- Implemented: Yes.

6. RecyclerView bottom spacing could feel tight with billing row + banner.
- Impact: perceived crowding near bottom content when ad is visible.
- Fix: added bottom padding + `clipToPadding=false` on home list.
- Implemented: Yes.

### Low
7. Ad unexpected-state telemetry lacked throwable details in event payload.
- Impact: harder investigation of runtime ad state anomalies.
- Fix: added throwable type/message to `ad_unexpected_state` tracking + debug line.
- Implemented: Yes.

## Files Changed
- `app/src/main/java/com/milen/grounpringtonesetter/ui/home/GroupsAdapter.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/ui/home/HomeGroupCardAccessibilityText.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/ui/home/HomeScreen.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/customviews/ui/buttons/CustomTextColorImageButton.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/customviews/ui/buttons/CustomRoundedButton.kt`
- `app/src/main/java/com/milen/grounpringtonesetter/customviews/ui/ads/AdDiagnostics.kt`
- `app/src/main/res/layout/item_group_entity.xml`
- `app/src/main/res/layout/fragment_home_screen.xml`
- `app/src/main/res/layout/custom_text_color_button.xml`
- `app/src/main/res/layout/custom_toolbar_view.xml`
- `app/src/main/res/menu/home_actions_dropdown.xml`
- `app/src/main/res/values/dimens.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-bg/strings.xml`
- `app/src/main/res/values-de/strings.xml`
- `app/src/main/res/values-fr/strings.xml`
- `app/src/main/res/values-hi/strings.xml`
- `app/src/main/res/values-it/strings.xml`
- `app/src/main/res/values-ja/strings.xml`
- `app/src/main/res/values-pl/strings.xml`
- `app/src/main/res/values-ru/strings.xml`
- `app/src/main/res/values-zh/strings.xml`
- `app/src/test/java/com/milen/grounpringtonesetter/ui/home/HomeGroupCardAccessibilityTextTest.kt`

## Manual Verification Still Required
- TalkBack behavior on physical devices (Android 12/13/14+) for:
  - card summary order
  - action button announcements
  - focus order with banner visible
- Visual checks on smallest supported screens at larger font/display sizes.
- Real ad rendering behavior in debug/release QA tracks.

## Manual QA Checklist - TalkBack
- Enable TalkBack and navigate the entire main screen.
- Confirm every action button has a meaningful announcement.
- Confirm each group card announces: group name, contacts count, ringtone.
- Confirm Home actions -> Info is reachable and fully readable.
- Confirm Choose Ringtone announcement includes the group name.
- Confirm Edit/Delete/Manage Contacts announcements include the group name.
- Increase system font size (1.3x and 1.5x) and verify usability.
- Verify with a very long group name.
- Verify with a very long ringtone filename.
- Verify with no ringtone assigned.
- Verify with zero contacts in a group.

## Manual QA Checklist - Ads
- Confirm home banner does not cover core list content.
- Confirm last list item remains reachable with banner visible.
- Confirm interstitial flow remains at natural breaks only.
- Confirm debug builds use test ad units and release uses production ad units.
- Confirm Ad Inspector can open via debug long-press on app version.

## Manual QA Checklist - Permissions
- Deny contacts permission and verify graceful messaging.
- Deny ringtone/media permission and verify graceful messaging.
- Re-grant permissions and confirm group list/actions recover correctly.
- Verify behavior when selected ringtone file is missing/unavailable.

## Validation Commands
- `./gradlew :app:processDebugMainManifest`
- `./gradlew :app:processReleaseMainManifest`
- `./gradlew :app:assembleDebug`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:lintDebug`
- `./gradlew test`

## Validation Results
- `:app:processDebugMainManifest` ✅ (manifest merge warning only: `tools:replace` present without conflicting provider in current merge graph)
- `:app:processReleaseMainManifest` ✅
- `:app:assembleDebug` ✅
- `:app:testDebugUnitTest` ✅
- `:app:lintDebug` ✅
- `test` ✅
- During execution there were transient Kotlin daemon incremental cache errors; re-running compile/build tasks completed successfully without code changes.

## Notes
- Keystore-related files are ignored by `.gitignore` (`keystore.properties`, `*.jks`, `*.keystore`) and are not tracked in git.
