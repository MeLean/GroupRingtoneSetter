# Release Smoke Test

Run this checklist on a Play-installed release candidate using a dedicated license-test account and
Google test ads. Do not use production purchases or click production advertisements.

## Contacts and ringtone flow

1. Grant contacts permissions and select both an on-device source and a cloud account when present.
2. Create a group, add two contacts, rename it, assign a ringtone, and confirm both contacts ring with it.
3. Remove one member, delete the group, and confirm unrelated contacts remain unchanged.
4. Reset all ringtones and verify the confirmation and final contact state.

## Device defaults and backup

1. Set ringtone, notification, and alarm defaults, including a custom notification under the duration limit.
2. Deny and then grant the system-settings permission; verify pending selection is applied only once.
3. Export a `.grs` backup, change the groups and defaults, restore it, and verify the preview and result counts.
4. Cancel one export and one restore and confirm no partial success is reported.

## Billing, ads, consent, and connectivity

1. With a non-owned license-test account, complete the remove-ads test purchase and restart the app.
2. Verify entitlement restoration, hidden banners, unlocked backup/restore, and no interstitial after a ringtone change.
3. With a clean non-owned test account, verify consent/privacy options, a Google test banner, and a Google test interstitial.
4. Disable networking: an ad-supported user reaches the no-internet screen and returns after reconnecting; an owned user can continue using offline features.

Record device model, Android version, app version, account state, and any failed step in the release notes.
