# Contact count clipping — Task 2 local review

Baseline: `63c029ad1372679f246e1cc705d816f513178662` (8.7.3).
No staging, commits, pushes, merges, rebases, tags, releases or deployment performed.

## Scope and preservation

Initial `git status --short` contained only `?? .kotlin/`; there was no tracked
Task 1 patch to include. That pre-existing directory was left untouched. Advertising
code, resources and build configuration are unchanged by Task 2. Existing AdMob
history/documentation is in the baseline; it cannot safely be identified as the
requested Task 1 patch without its commit or saved diff. Review.patch includes all
tracked local changes and all new Task 2 source/documentation files without staging.
It excludes the pre-existing `.kotlin/` cache and the patch itself.

## Investigation

- Device available: `Gate3_API_35_Pixel9`, Android 15/API 35, 1280x2856, 480dpi,
  initial system font scale 1.0. A Pixel 10a was unavailable.
- Temporary `CODEX_DEBUG` instrumentation in the Android rendering test logged
  measured badge bounds, padding, text width and available text width. Removed.
- Inherited MaterialButton horizontal padding: 48px per side at 480dpi (16dp).
  Fixed width: 168px (56dp). Available text width: 72px (24dp).
- Baseline red test: `90`, font scale 2.0, 480dpi, 320dp card width, classic theme:
  text required **95px**, available **72px**. This proves an undersized text area
  on the emulator. It does not prove the customer's exact font/density settings.
- MaterialButton minimum dimensions/insets cannot expand an EXACTLY 56dp layout.
  The 60dp drawable intrinsic size also cannot override those exact dimensions.
  Increasing display density keeps the same insufficient dp budget; font scaling
  increases text demand. Fixed constraints also prevent an expanding badge from
  sharing the original narrow ringtone-description column reliably.
- Adapter binding uses the full `contacts.size` string and refreshes it every bind.
  No count-calculation defect was established; calculation remains unchanged.
- No global theme or shared drawable changes are necessary. The oval drawable
  stretches with the content-sized badge, including its existing intrinsic minimum.

## Implementation

- `CircleWIthText.kt`, `CircleWithText` constructor: AppCompatTextView with
  textViewStyle instead of MaterialButton/materialButtonStyle; retains background
  and `setColors` theme tinting.
- `item_group_entity.xml`: wrap-content single-line badge with explicit centered
  14sp text and padding; system scaling remains enabled. Badge grows into an oval.
  Ringtone button uses the remaining row width; a bottom barrier places the existing
  ringtone description on its own full-width line. Title minimum height preserves
  space for its 48dp action buttons even with a short name.
- `GroupsAdapter.kt`, `ViewHolder.bind`: removes the redundant button-to-text
  accessibility delegate. Existing localized full-count description, non-clickable
  state, contact calculation and action listeners remain.
- `GroupCountLayoutTest.kt`: real Android inflation, measurement and drawing plus
  live RecyclerView scrolling. No pure JVM formatting test is used as rendering proof.

## Automated checks

Results are recorded below after execution. The rendering matrix covers:

- Counts 0, 9, 10, 90, 100, 999, 1000, followed by rebinds from wide to narrow/wide.
- Font scales 1.0, 1.3, 1.5, 2.0.
- Density 480 and 600dpi (increased Display Size equivalent via Configuration).
- Card widths 288, 328, 608dp, accounting for 32dp outer margins on screens of
  320, 360, 640dp. The widest case represents landscape width.
- Classic, dark high contrast and light high contrast themes.
- Long group names and long ringtone filenames.
- Full painted-text width and height, one line, no number ellipsis, card bounds,
  no overlap with the button/description, non-interactive semantics, full localized
  accessibility text and theme text colors. Representative bitmaps are drawn.
- Live scrolling through 35 groups with mixed counts, revisiting recycled positions.

## Reproducible manual QA

1. On a disposable emulator/device, create groups with 0, 9, 10, 90, 100, 999 and
   1000 members (or seed `RegressionTestApplication.fakeContacts` in an Android test).
   Give groups long names and choose a long ringtone filename.
2. Open the main group list. Verify every digit, centered alignment, oval background,
   readable ringtone description and visible manage/edit/delete/choose actions.
3. Repeat at font settings 100%, 130%, 150%, 200% and increased Display Size. Include
   a 320dp logical screen width, portrait/landscape, and all three app themes.
4. Scroll enough to recycle rows, return to groups with different count lengths,
   recreate the activity and check that numbers and descriptions match each group.
5. Enable TalkBack and swipe through cards. Expect the localized equivalent of
   “Group contact count: 1000”, without a button role or click action on the count.
   Verify manage/edit/delete/choose actions still announce their purpose and work.
6. Return font, Display Size, orientation, theme and accessibility settings to their
   previous values. The automated configuration matrix does not change system settings.

## Risks and limits

Cards are taller because ringtone descriptions occupy a separate line. Long titles
and ringtone descriptions retain their existing ellipsis policies; the count does not
ellipsis. Four-digit counts at up to 200% scaling and screen widths down to 320dp
are the intended verified range. More than four digits, narrower logical screens,
or custom font scales above 200% require further layout testing. This change does
not alter contacts, ringtone assignment, permissions, navigation or advertising.
Physical Pixel 10a reproduction and human evaluation of spoken TalkBack output
remain separate checks; accessibility metadata tests are not a listening test.
