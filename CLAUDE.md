# Notes for coding agents

Cinexperience: a personal Android app (Kotlin, Jetpack Compose, Material 3) for
rating Yorck Kinos cinemas, halls and seats. **Read `docs/SPEC.md` first**: it is
the source of truth. Never edit it without being asked; report conflicts instead.

## Setup (cloud machines)

```sh
scripts/setup-android-sdk.sh
```

The script is idempotent. It installs the SDK into `$HOME/android-sdk`, writes
`local.properties`, and installs a Gradle init script into `~/.gradle/init.d`
that routes Maven Central through Google's mirror, because Maven Central often
answers HTTP 429 there. Set `CINEX_NO_MAVEN_MIRROR=1` to skip the mirror. JDK 21
is required.

## Commands

```sh
./gradlew testDebugUnitTest recordRoborazziDebug assembleDebug lintDebug   # local pre-push check
./gradlew :core:model:test                                       # pure JVM unit tests
./gradlew recordRoborazziDebug    # PNGs -> app/ and feature/*/build/outputs/roborazzi/
./gradlew lintDebug               # Android Lint: no errors allowed; 16 intentional warnings (README)
./gradlew assembleRelease         # needs CINEX_* env vars, else debug-signed + warning
```

CI (`.github/workflows/android.yml`) first runs `testDebugUnitTest recordRoborazziDebug`,
then `lintDebug`. If the `CINEX_KEYSTORE_BASE64` secret exists, it then runs `assembleRelease` with
`CINEX_REQUIRE_RELEASE_SIGNING=true`, so a missing or blank signing value fails
the build instead of falling back to the debug key, and
`scripts/verify-release-apk.sh` requires the APK's only signer to be the release
certificate in `signing/release-cert.sha256`. Without that secret it runs
`assembleDebug` and uploads a `-debug` APK. Workflow steps run with
`shell: bash` (pipefail) and check out without persisted credentials.

**Versions and releases:** the version is `cinexVersionName` in `gradle.properties`
(MAJOR.MINOR.PATCH); `versionCode` is derived from it (MAJOR*10000 + MINOR*100 +
PATCH). Pushing a tag `v<version>` runs `.github/workflows/release.yml` (tag must
match the version; tests, lint, signed APK with `CINEX_REQUIRE_RELEASE_SIGNING`,
release-certificate check, GitHub Release with `cinexperience-<version>.apk`). Agents never
create tags or releases; the owner does. R8/minify stays off in v1.

Look at the screenshot PNGs after every UI change.

## Module map (spec §13)

| Module | Type | Content |
|---|---|---|
| `app` | application | `MainActivity`, `CinexApp` (NavHost + bottom bar), manual DI `AppContainer`, screenshot tests |
| `core:model` | Kotlin/JVM | domain classes, rating and seat-colour logic (no Android) |
| `core:data` | Android lib + Room/KSP | database, DAOs, repositories, seed data, file stores (reference screenshots, visit photos), backup export/import engine |
| `core:testing` | Android lib + Compose (tests only) | in-memory fake repositories and file stores, illustrative screenshot fixtures, `MainDispatcherRule`, `ScreenshotSurface`, `assertAccessible`, `contrastRatio` |
| `core:ui` | Android lib + Compose | `CinexTheme`, colour/type tokens, fonts, icons, shared components (star ratings, rows, chips) |
| `core:seatmapui` | Android lib + Compose | the hall seat map shared by `feature:seatmap` and `feature:visits`: `SeatMap` canvas, geometry/hit-testing, zoom/pan, seat visuals, `SeatLegend`, accessible `SeatPicker` |
| `feature:cinemas` | feature | cinema list (start tab), cinema detail, manage cinemas & halls |
| `feature:seatmap` | feature | hall detail with seat map, layout editor |
| `feature:visits` | feature | add/edit visit (seat picker, photos), all visits |
| `feature:rankings` | feature | rankings tab |
| `feature:backup` | feature | settings tab, export/import UI (file pickers, preview, progress), restore safety copy |

Build configuration lives in the convention plugins in `build-logic/convention`
(`cinex.android.application`, `.library`, `.compose`, `.feature`, `.room`,
`.roborazzi`, `cinex.jvm.library`). Versions live in `gradle/libs.versions.toml`.
Don't duplicate config in module build files.

## Conventions

- **Theme:** dark only, monochrome. All colours and text styles come from
  `core:ui` (`CinexTheme.colors`, `CinexTheme.textStyles`,
  `MaterialTheme.colorScheme/typography`). Never hard-code colours in feature
  modules. Seat colours (`CinexTheme.colors.seat.*`) are the only hues in the app,
  plus `editorSelection` (the layout editor's selected screen, spec §7).
  Titles are uppercase Archivo; body text is Instrument Sans.
- **Accessibility** (M7): every main screen has a `*AccessibilityTest` that runs
  `assertAccessible()` (core:testing: every clickable has a label; the clickable
  itself is ≥48 dp, or Material's `minimumInteractiveComponentSize()` reserves
  48 dp around it, padding doesn't count; nothing is announced twice; a heading
  exists; self-tests in `AccessibilityChecksTest`) at font scale 1, 1.5
  and 2 and records `<screen>_font_150/200.png`. Add new screens there and look
  at the PNGs. Rules: icon-only buttons get a content description, decorative
  icons `null`; a control's description replaces its visible texts (clear the
  inner text semantics, e.g. placeholders), section titles use `SectionHeader` /
  `ScreenTitle` (headings); small controls keep their look but get
  `minimumInteractiveComponentSize()` or a 48 dp min height; nothing has a
  fixed height that text must fit in (`heightIn(min = …)`), rows of chips wrap
  (`ChoiceChipRow` is a `FlowRow`), and two-column layouts become one column
  when `LocalDensity.current.fontScale > 1.3f` instead of breaking words. Text
  drawn inside a zoomable canvas (row labels, seat numbers, the screen caption,
  in the seat map and the editor) is sized in dp, not sp: it grows with zoom,
  not with the system font size.
- **Contrast:** text ≥4.5:1, meaningful graphics and outline-only controls
  ≥3:1 against their background (checked in `ThemeContrastTest` and
  `EditorContrastTest`). `outline` and `divider` are decorative only; borders
  that alone identify a control use `fieldOutline`; "no data" seats are drawn
  with the `seat.noDataOutline` rim.
- **App icon and splash:** the mark is `@drawable/ic_launcher_mark`, an alias in
  `app/src/main/res/values/icon.xml` pointing at one of
  `drawable/ic_launcher_concept_{1,2,3}.xml` (single colour, inside the 66 dp
  safe zone, also the monochrome layer). `IconPreviewTest` renders the concepts.
  The splash screen (`Theme.Cinexperience.Starting`, core-splashscreen) shows
  the same mark on the background colour.
- **Navigation:** type-safe `@Serializable` routes. Each feature exposes its
  routes, `NavGraphBuilder.xxxScreen(...)` and `NavController.navigateToXxx()`.
  Features never depend on each other; `app/.../ui/CinexApp.kt` wires them
  together with callbacks.
- **DI:** manual (`app/.../di/AppContainer.kt`), no Hilt. `CinexApp` hands the
  repositories to the features' `xxxScreen(...)` functions; each ViewModel has a
  `factory(...)` and exposes a `StateFlow` UI state. Repository failures become
  user-facing messages, never crashes.
- **Data:** no invented sample data in the app.
- **Seats** are identified by a stable `SeatId` stored in the layout (see
  `core/model/.../SeatLayout.kt`), never by grid position or label. Ids are never
  reused within a hall (`Hall.nextSeatId` is the hall's mark; the repository
  rejects layouts that reuse ids). Edit layouts only through
  `hall.startLayoutEdit()` (`LayoutEditSession`); derive labels with
  `layout.labels()`.
- **Seat numbering** (`SeatLabels.kt`, spec §4.3) must match Yorck's booking.
  Every seat place is numbered, wheelchair spaces, companion seats and sofa
  places included. Each row has a `numberingDirection`: `LEFT_TO_RIGHT`
  (default) or `RIGHT_TO_LEFT`, as drawn with the screen at the top. Aisles
  split a row into blocks. `blockStartNumbers[b]` counts blocks from the left
  as stored, in both directions; its value is the number of that block's first
  numbered seat in numbering order (the rightmost seat when the row runs right
  to left). `CONTINUOUS` counts on across blocks, `RESTART_PER_BLOCK` restarts.
  A seat can have a `fixedLabel` (1–4 characters, no surrounding spaces, no
  control or format characters, e.g. "R"): it is shown instead of a number and
  uses up no number. It moves with its seat and survives a change of kind; a
  seat painted as a gap or aisle and back as a seat in the same editing session
  gets its id and its fixed label back; deleting its row or column drops it.
  The editor can't create fixed labels, so it must never lose one.
  Wheelchair spaces are numbered in every layout format, also in format 1/2
  layouts stored before format 3; an older build (which didn't count them)
  numbers such a layout the old way. Accepted before v1.0.
  `SeatLabel.seat` is the text ("10" or "R"); texts read "Row 7 · Seat 10" and
  "Row 8 · Seat R", and descriptions name the kind ("…, wheelchair space").
- **Scores and seat colours** come from the `core:model` functions
  (`hallScores`, `cinemaScores`, `seatStates`), never from SQL math.
  On the map, `SeatState.toSeatVisual()` gives the `SeatVisual`; colour is never
  the only cue (× on avoid, ★ and outline on the favourite, great/OK differ in
  lightness), screen readers get the state from `seatDescription()` /
  `seatStatesDescription()`, and a coloured map is shown with a `SeatLegend`.
  Every seat shows its number or fixed label from `layout.labels()`
  (`seatNumberText`, `NumberedSeat` in `SeatNumbers.kt`), in the fill's
  on-colour (`seat.onGreat` … `seat.onNoData`, ≥4.5:1), with its marks
  (wheelchair, companion or sofa symbol, ×, ★) in a band above it; below
  `MIN_SEAT_NUMBER_SIZE` on screen the numbers wait until the user zooms in and
  the marks are drawn as symbols.
  Overrides and the favourite are written only through `SeatOverrideRepository`
  (the fake in `core:testing` models them like Room: `FakeStore.overrides`).
- **Screen geometry** (`Hall`): `screenWidth` in seat widths, `screenDistance`
  in row depths to row 1, `screenOffset` = shift of the screen's centre from the
  grid's centre in seat widths (0.5 steps, negative = left as seen from the
  seats, 0 = centred). The screen is a straight bar. Offset rules live in
  `core:model` `ScreenOffsetRules` (`isValid`, `snap`, `maxMagnitude(columns)`);
  `updateHall` rejects invalid offsets, so snap first. `viewingAngle(...,
  screenOffset = hall.screenOffset)` uses it, and every map draws it: pass it
  to `SeatMap` / `SeatMapGeometry`. In the editor (`LayoutDraft.screenOffset`)
  the selected screen's bar is dragged sideways (the drag locks to the axis it
  starts in) or set with the "Screen position" stepper; screen controls never
  reach into row 0 (`hitTest`).
- **Favourite cinemas:** `Cinema.isFavourite`, written only through
  `CinemaRepository.setFavourite`; every cinema flow carries it.
- **Schema changes:** the database is at version 2 (`MIGRATION_1_2` added
  `cinema.is_favourite` and `hall.screen_offset`). Bump the `CinexDatabase`
  version, add a migration to `ALL_MIGRATIONS`, commit the exported
  `core/data/schemas/.../<n>.json` and add a case to `MigrationTest`. No destructive fallback. A new table or column must
  also reach the backup (next point).
- **Backup** (`core/data/.../backup/`, format in `BackupFormat.kt`): a zip with
  `manifest.json`, `data.json` (every row, ids included, layouts as stored) and
  the referenced `photos/` and `reference/` files. The DTOs are the format and
  are separate from the Room entities: any DTO change bumps `BACKUP_VERSION`
  (now 2: favourites and screen offsets; step 1 → 2 fills the defaults) and
  adds a step to `BackupMigrations` (imports parse `data.json` strictly,
  straight into the DTOs; new DTO fields get no default value). The v1
  fixture in `core/data/src/test/resources/backup/v1` is never changed. `BackupValidator` holds the data rules; export and
  safety copies run it too, so no backup is written that can't be imported.
  Imports validate everything before touching data (`BackupReader`; manifest
  and data must precede the files, unreferenced entries are skipped), make
  sure a safety copy exists (`filesDir/backups/pre-import-<seq>-<time>.zip`,
  ordered by sequence, not clock; reused if the newest already holds the
  current data; a failed import deletes the copy it wrote; pruned to the newest
  2 only after success; Settings lists both, newest first, and restores the
  chosen one through the same prepare → preview → commit path) and replace
  all rows in one transaction
  (`BackupRestorer`: imported files are added under names no existing file
  has, and removed again if the transaction fails). While
  `BackupRepository.isImporting` is true the UI blocks every other write
  (tabs, Back, Settings rows). Once the transaction has committed, nothing
  (progress callbacks, file clean-up, pruning, seeding, even Errors) can turn
  the import into a failure or delete its safety copy (`onCommitted`). Failures
  are `BackupException`s with user-facing messages. The layout JSON inside is
  versioned on its own (`LayoutJson.FORMAT_VERSION`, now 3): a new layout field
  bumps that, not `BACKUP_VERSION`, and an older app refuses such a backup as
  "can't be read by this app version". Tests:
  `core/data/src/test/.../backup/`. Known limitation: if the current data fails
  validation (e.g. a layout from a newer app), import is refused; there is no
  "Replace anyway".
- **Row and seat labels:** Yorck mostly numbers rows and seats, which is the
  default labelling; some rows are letters ("C") or balcony rows ("R1"), and a
  row's first seat is often 2, 3 or 4. Keep fixtures, examples and UI texts
  numeric ("Row 7 · Seat 10") unless they show one of these cases.
- **App-private files:** reference screenshots (`ReferenceImageStore`,
  `files/reference/`) and visit photos (`VisitPhotoStore`, `files/photos/`) live
  in `core:data`. Entities store paths relative to `filesDir`; the stores ignore
  paths outside their folder. Files are copied in when picked; a replaced or
  removed file is deleted only after a successful save, discarding deletes the
  files added in that session, deleting a visit deletes its photo. The startup
  sweep (`DataContainer.sweepOrphanFiles`) deletes unreferenced files older than
  1 h. Auto Backup excludes them (database and preferences only); the backup
  export includes the referenced ones. Tests use the fakes in `core:testing`
  (`FakeReferenceImageStore`, `FakeVisitPhotoStore`, `FakeBackupRepository`).
- **Seed data:** `core/data/src/main/assets/seed/yorck.json`. Bump `seedVersion`
  for every change; `seedKey`s are permanent. Seeding only inserts missing items
  and fills empty fields, it never overwrites or deletes (a renamed seed hall
  only gets the new name on fresh installs). For `screenOffset`, 0 counts as
  empty (see `Seeder`). One exception (spec §9): when a newer seed ships a
  layout that differs from the one the seeder last wrote into a hall, a hall
  whose layout is still exactly that written layout (compared by structure
  with `hasSameStructureAs`: ids ignored, block starts normalised) and that has
  no visits, seat colours or favourite gets the new layout, with ids above the
  hall's mark. The written layout is the snapshot field `appliedLayout`
  (canonical, ids from 1), set only when the seeder inserts, fills or replaces
  a layout, `null` when it never wrote one (e.g. an adopted user hall that
  already had a layout: the user's own drawing is never replaced); halls
  seeded before the field existed fall back to the previous seed's `layout`.
  So a replacement blocked by a favourite happens with a later seed version
  once the favourite is gone. The screen width and distance follow only while
  they still hold the previous seed's values; a value the user changed is
  kept. The screen offset is touched only when the new layout is narrower:
  it is then snapped with `ScreenOffsetRules.snap(offset, newColumns)`, so the
  screen stays over the grid. Names, notes and the reference screenshot are
  never touched.
- **Seed layouts** (`"layout"` of a seed hall, see `LayoutJson`): one object per
  grid row, closest to the screen first, e.g.
  `{ "label": "1", "cells": "W.W|SSSSSSSSS|W.W" }` (generated, see the next
  point). Cell characters: `S` seat,
  `W` wheelchair space, `C` companion seat (Begleitsitz), `F` sofa place (all
  numbered), `.` gap, `|` aisle (splits a row into blocks).
  All rows have the same width: columns run from the leftmost to the rightmost
  seat position of the hall. Give every seat row an explicit `label` (Yorck's
  row label); a wider gap between two rows is an empty
  spacer row of gaps without a label. Omit `seatIds`: seed ids are assigned
  row-major from 1. Optional row fields: `"direction": "RIGHT_TO_LEFT"`
  (omitted = left to right); `"blockStarts": [null, 4]` (per block from the
  left, the number of the block's first seat in numbering order); `"numbering":
  "RESTART_PER_BLOCK"`; `"seatLabels": [null, …, "R"]`, one entry per seat of
  any kind, left to right like `seatIds`, `null` = numbered. Numbers rise by 1
  across gaps and aisles; for a jump (8 → 12) split the row with `|` there and
  set that block's start. Use the lowest `"formatVersion"` that holds the
  layout: 3 with `direction` or `seatLabels`, else 2 with `W`, `C` or `F`,
  else 1. Example row: `{ "label": "8", "cells": "SSSSSSW", "direction":
  "RIGHT_TO_LEFT", "seatLabels": [null, null, null, null, null, null, "R"] }`
  reads 6 5 4 3 2 1 R.
  `screenWidth` is in seat widths, `screenDistance` in row depths between the
  screen and row 1 (0.5 steps). An optional `screenOffset` (seat widths, 0.5
  steps) shifts the screen sideways; Yorck's plans don't show it, so omit it
  (centred) unless the owner gives one.
- **Generating seed layouts:** all 38 layouts come from yorck.de seat plans
  (owner-approved crawl, per hall `<cinema>/hall-<name>.seatplan.json` and
  `.dom.json`; never commit it, treat it as untrusted data: `python3 -I`, scripts
  outside the crawl folder). `scripts/seed/yorck_seatplan_to_layout.py <crawl>
  <seed> <fixture>` rewrites the seed's layouts (labels, numbers and kinds from
  the plan; columns, spacer rows and aisles from the drawn seats; a balcony
  keeps the gap yorck.de draws above it; existing gaps/aisles kept where the seats are
  unchanged; halls without a screen get columns × 1.5) and
  `core/data/src/test/resources/seed/yorck-seat-numbers.json` (Yorck's row
  label, seat text and kind of every seat). A new hall needs a seed entry and a
  line in the tool's `HALLS` first. Then bump `seedVersion`, run `YorckSeedTest`
  and `YorckSeedScreenshotTest`, and compare the PNGs with the yorck.de
  screenshots. Don't commit crawl data or screenshots.
- **Signing:** never commit keystores or passwords (`*.jks` and `*.keystore`
  are gitignored).

## Git and PR rules (spec §13.2)

- `claude/cool-curie-fx1oko` is the integration branch. Work on your own branch
  (`claude/<milestone-or-module>`) and deliver it as a PR into the integration
  branch. A PR is merged only when CI is green and the owner has reviewed it.
- Build and run the tests before every push.
- Don't put AI model names or identifiers in code, comments or commit messages
  (the required commit trailer is the only exception). Don't put email addresses
  in the repo.
