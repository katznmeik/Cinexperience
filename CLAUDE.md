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
./gradlew testDebugUnitTest recordRoborazziDebug assembleDebug   # local pre-push check
./gradlew :core:model:test                                       # pure JVM unit tests
./gradlew recordRoborazziDebug    # PNGs -> app/ and feature/*/build/outputs/roborazzi/
./gradlew assembleRelease         # needs CINEX_* env vars, else debug-signed + warning
```

CI (`.github/workflows/android.yml`) first runs `testDebugUnitTest recordRoborazziDebug`.
If the `CINEX_KEYSTORE_BASE64` secret exists, it then runs `assembleRelease` with
`CINEX_REQUIRE_RELEASE_SIGNING=true`, so a missing or blank signing value fails
the build instead of falling back to the debug key. Without that secret it runs
`assembleDebug` and uploads a `-debug` APK.

Look at the screenshot PNGs after every UI change.

## Module map (spec §13)

| Module | Type | Content |
|---|---|---|
| `app` | application | `MainActivity`, `CinexApp` (NavHost + bottom bar), manual DI `AppContainer`, screenshot tests |
| `core:model` | Kotlin/JVM | domain classes, rating and seat-colour logic (no Android) |
| `core:data` | Android lib + Room/KSP | database, DAOs, repositories, seed data, file stores (reference screenshots, visit photos) |
| `core:testing` | Android lib (tests only) | in-memory fake repositories and file stores, illustrative screenshot fixtures, `MainDispatcherRule` |
| `core:ui` | Android lib + Compose | `CinexTheme`, colour/type tokens, fonts, icons, shared components (star ratings, rows, chips) |
| `core:seatmapui` | Android lib + Compose | the hall seat map shared by `feature:seatmap` and `feature:visits`: `SeatMap` canvas, geometry/hit-testing, zoom/pan, seat visuals, `SeatLegend`, accessible `SeatPicker` |
| `feature:cinemas` | feature | cinema list (start tab), cinema detail, manage cinemas & halls |
| `feature:seatmap` | feature | hall detail with seat map, layout editor |
| `feature:visits` | feature | add/edit visit (seat picker, photos), all visits |
| `feature:rankings` | feature | rankings tab |
| `feature:backup` | feature | settings tab, export/import |

Build configuration lives in the convention plugins in `build-logic/convention`
(`cinex.android.application`, `.library`, `.compose`, `.feature`, `.room`,
`.roborazzi`, `cinex.jvm.library`). Versions live in `gradle/libs.versions.toml`.
Don't duplicate config in module build files.

## Conventions

- **Theme:** dark only, monochrome. All colours and text styles come from
  `core:ui` (`CinexTheme.colors`, `CinexTheme.textStyles`,
  `MaterialTheme.colorScheme/typography`). Never hard-code colours in feature
  modules. Seat colours (`CinexTheme.colors.seat.*`) are the only hues in the app.
  Titles are uppercase Archivo; body text is Instrument Sans.
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
- **Scores and seat colours** come from the `core:model` functions
  (`hallScores`, `cinemaScores`, `seatStates`), never from SQL math.
  On the map, `SeatState.toSeatVisual()` gives the `SeatVisual`; colour is never
  the only cue (× on avoid, ★ and outline on the favourite, great/OK differ in
  lightness), screen readers get the state from `seatDescription()` /
  `seatStatesDescription()`, and a coloured map is shown with a `SeatLegend`.
  Overrides and the favourite are written only through `SeatOverrideRepository`
  (the fake in `core:testing` models them like Room: `FakeStore.overrides`).
- **Schema changes:** bump the `CinexDatabase` version, add a migration to
  `ALL_MIGRATIONS`, commit the exported `core/data/schemas/.../<n>.json` and add
  a case to `MigrationTest`. No destructive fallback.
- **Row and seat labels:** Yorck numbers rows and seats (1…n), which is the
  default labelling. Keep fixtures, examples and UI texts numeric ("Row 7 ·
  Seat 10"); custom row labels exist only for other cinemas.
- **App-private files:** reference screenshots (`ReferenceImageStore`,
  `files/reference/`) and visit photos (`VisitPhotoStore`, `files/photos/`) live
  in `core:data`. Entities store paths relative to `filesDir`; the stores ignore
  paths outside their folder. Files are copied in when picked; a replaced or
  removed file is deleted only after a successful save, discarding deletes the
  files added in that session, deleting a visit deletes its photo. The startup
  sweep (`DataContainer.sweepOrphanFiles`) deletes unreferenced files older than
  1 h. Auto Backup excludes them (database and preferences only); the M6 export
  will include them. Tests use the fakes in `core:testing`
  (`FakeReferenceImageStore`, `FakeVisitPhotoStore`).
- **Seed data:** `core/data/src/main/assets/seed/yorck.json`. Bump `seedVersion`
  for every change; `seedKey`s are permanent. Seeding only inserts missing items
  and fills empty fields, it never overwrites or deletes (a renamed seed hall
  only gets the new name on fresh installs).
- **Seed layouts** (`"layout"` of a seed hall, see `LayoutJson`): one object per
  grid row, closest to the screen first, e.g.
  `{ "label": "1", "cells": "W.W|SSSSSSSSS|W.W" }`. Cell characters: `S` seat,
  `W` wheelchair space (not counted in seat numbers), `C` companion seat
  (Begleitsitz), `F` sofa place, `.` gap, `|` aisle (splits a row into blocks).
  All rows have the same width: columns run from the leftmost to the rightmost
  seat position of the hall. Give every seat row an explicit `label` (Yorck
  numbers rows 1..n from the screen); a wider gap between two rows is an empty
  spacer row of gaps without a label. Omit `seatIds`: seed ids are assigned
  row-major from 1. Use `"formatVersion": 2` when a layout has `W`, `C` or `F`.
  `screenWidth` is in seat widths, `screenDistance` in row depths between the
  screen and row 1 (0.5 steps; the screen is always centred over the grid).
- **Adding transcribed halls:** measure the Yorck-app screenshot (seat box
  centres → columns by the seat pitch, rows by the row pitch; check each icon),
  mark an empty column between two blocks as `|` in the rows where both blocks
  exist and other empty cells as `.`, set screen width = lower edge of the drawn
  screen ÷ seat pitch and distance = gap to row 1 ÷ row pitch. Add the layout
  to the hall's seed entry, bump `seedVersion`, add a case like
  `DelphiLuxSeedTest` (seat counts per row and kind, spot-checked labels) and a
  render like `DelphiLuxSeedScreenshotTest`, and compare the PNG with the
  screenshot row by row. Don't commit the screenshots.
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
