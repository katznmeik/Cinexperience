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
is required. Downloads are checked against the published checksums: the
command-line tools zip in the script (`CMDLINE_TOOLS_SHA1`, Google publishes only
SHA-1) and the Gradle distribution (`distributionSha256Sum` in
`gradle-wrapper.properties`); a version update changes the checksum too.

## Commands

```sh
./gradlew testDebugUnitTest recordRoborazziDebug assembleDebug lintDebug   # local pre-push check
./gradlew :core:model:test                                       # pure JVM unit tests
./gradlew recordRoborazziDebug    # PNGs -> app/ and feature/*/build/outputs/roborazzi/
./gradlew lintDebug               # Android Lint: no errors allowed; 16 intentional warnings (README)
./gradlew assembleRelease         # needs CINEX_* env vars, else debug-signed + warning
python3 -I -B -m unittest discover -s scripts/seed -p 'test_*.py'   # seed generator tests
```

Unit tests run in UTC on every machine and list skipped tests in the build
output (`configureUnitTests` in build-logic).

CI (`.github/workflows/android.yml`) first runs the seed generator tests, then
`testDebugUnitTest recordRoborazziDebug`, then `lintDebug`. If the
`CINEX_KEYSTORE_BASE64` secret exists, it then runs `assembleRelease` with
`CINEX_REQUIRE_RELEASE_SIGNING=true`, so a missing or blank signing value fails
the build instead of falling back to the debug key, and
`scripts/verify-release-apk.sh` requires the APK's only signer to be the release
certificate in `signing/release-cert.sha256`. Without that secret it runs
`assembleDebug` and uploads a `-debug` APK. Workflow steps run with
`shell: bash` (pipefail) and check out without persisted credentials. The
workflow actions are pinned to commit SHAs with a version comment, so updating
one means changing its SHA.

**Versions and releases:** the version is `cinexVersionName` in `gradle.properties`
(MAJOR.MINOR.PATCH); `versionCode` is derived from it (MAJOR*10000 + MINOR*100 +
PATCH). `.github/workflows/release.yml` publishes a version (tag must match the
version; tests, lint, signed APK with `CINEX_REQUIRE_RELEASE_SIGNING`,
release-certificate check, GitHub Release with `cinexperience-<version>.apk`). It
runs on a pushed tag `v<version>` or manually (`workflow_dispatch`, input
`version`, default branch only): a manual run refuses an existing tag and creates
`v<version>` on the commit it built. Agents release only when the owner
explicitly asks for that version ("release v1.0"): run the workflow with the
version on the integration branch (GitHub connector, `run_workflow`), then check
that the run succeeded and the release has the APK. Agents never push tags and
never release on their own. R8/minify stays off in v1.
The build ID (`BuildConfig.BUILD_ID`, first 7 characters of `git rev-parse HEAD`,
`unknown` without git) is set in `app/build.gradle.kts`, shown in Settings → About
and matches the CI APK name `cinexperience-<sha>.apk`.

Look at the screenshot PNGs after every UI change.

## Module map (spec §13)

| Module | Type | Content |
|---|---|---|
| `app` | application | `MainActivity`, `CinexApp` (NavHost + bottom bar), manual DI `AppContainer`, screenshot tests |
| `core:model` | Kotlin/JVM | domain classes, rating and seat-colour logic (no Android) |
| `core:data` | Android lib + Room/KSP | database, DAOs, repositories, seed data, file stores (reference screenshots, visit photos), backup export/import engine |
| `core:program` | Android lib + kotlinx.serialization | Yorck's program (v1.1, spec §11.1): model (`Program`, `Film`, `Showing`), page parser, Yorck-name → seed-key table, `ProgramLinks`, visit pre-fill, the HTTPS download (the app's **only network code**), cache `files/program/program.json`, `ProgramRepository` |
| `core:ticket` | Android lib (+ Robolectric tests) | Yorck's ticket PDF (v1.4, spec §11.2): the own small PDF reader (`pdf/`), text in reading order (`text/`), the ticket fields (`TicketPdfReader` → `TicketPdfContent`, `TicketPdfRules`), the QR code's module grid (`qr/`, `QrModuleGrid`); pure Kotlin except the JPEG decoding (`BitmapLuminanceDecoder`) |
| `core:testing` | Android lib + Compose (tests only) | in-memory fake repositories (incl. `FakeProgramRepository`) and file stores, illustrative screenshot fixtures (incl. `buildProgram`, `SampleProgram`), `MainDispatcherRule`, `ScreenshotSurface`, `assertAccessible`, `contrastRatio` |
| `core:ui` | Android lib + Compose | `CinexTheme`, colour/type tokens, fonts, icons, shared components (star ratings, rows, chips, messages, the message snackbar, `rememberLoadedImage`, `rememberTextWidth`, picker date conversions), date and time texts (`text/Dates.kt`), `flow/Flows.kt` (`minuteTicks`, `asResult`), `navigation/` (result message for the screen below, `CloseOnce`) |
| `core:seatmapui` | Android lib + Compose | the hall seat map shared by `feature:seatmap` and `feature:visits`: `SeatMap` canvas, geometry/hit-testing, zoom/pan, seat visuals, `SeatLegend`, accessible `SeatPicker` |
| `feature:cinemas` | feature | cinema list (start tab; sort chips and the Favourites filter), cinema detail (☆/★ favourite toggle, "Now showing"), manage cinemas & halls |
| `feature:seatmap` | feature | hall detail with seat map, layout editor |
| `feature:visits` | feature | add/edit visit (seat picker, photos), all visits |
| `feature:rankings` | feature | rankings tab |
| `feature:backup` | feature | settings tab, export/import UI (file pickers, preview, progress), restore safety copy, Settings → Program |
| `feature:program` | feature | Program tab (v1.1, spec §6.9: consent, filters, saved filters, results), film page, `ProgramFilterStore` (filters in shared preferences) |

Build configuration lives in the convention plugins in `build-logic/convention`
(`cinex.android.application`, `.library`, `.compose`, `.feature`, `.room`,
`.roborazzi`, `.robolectric`, `cinex.jvm.library`). Versions live in `gradle/libs.versions.toml`.
Don't duplicate config in module build files.

## Conventions

- **Theme:** dark only, monochrome. All colours and text styles come from
  `core:ui` (`CinexTheme.colors`, `CinexTheme.textStyles`,
  `MaterialTheme.colorScheme/typography`). Never hard-code colours in feature
  modules. Seat colours (`CinexTheme.colors.seat.*`) are the only hues in the app,
  plus `editorSelection` (the layout editor's selected screen, spec §7).
  `colorScheme.error` is the text colour, not red: an error shows by its
  message and `isError`.
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
  when `isLargeText()` (core:ui: font scale > 1.3) instead of breaking words. Text
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
  together with callbacks. Four tabs (`TopLevelDestination`), in this order:
  Cinemas · Program · Rankings · Settings. "Show all in Program" (cinema detail)
  switches to `ProgramRoute(cinemaSeedKey)` with the tab options; "Log visit"
  opens `AddVisitRoute` with pre-fill arguments (`VisitPrefillArgs`, core:model).
- **Transitions** (spec §7) live only in `app/.../ui/CinexTransitions.kt`, passed
  to the NavHost in `CinexApp` (features never set their own): opening a screen
  slides it in from the right while the old one slides out left by the full
  width (no overlap, no crossfade), Back and the predictive back gesture reverse
  it (the gesture seeks a linear slide), switching tabs is a 90 + 210 ms
  fade-through; pushes and pops take 300 ms. The bottom bar slides and resizes
  with them. Every destination draws an opaque background (`ScreenBackground`
  in core:ui around the entry composable). `TransitionFramesTest` (app) captures
  frames at 0/25/50/75/100 % into `app/build/outputs/transitions` (CI uploads
  them); look at them after changing transitions.
- **DI:** manual (`app/.../di/AppContainer.kt`), no Hilt. `CinexApp` hands the
  repositories to the features' `xxxScreen(...)` functions; each ViewModel has a
  `factory(...)` and exposes a `StateFlow` UI state. Repository failures become
  user-facing messages, never crashes. Screens that read the time get
  `AppContainer.clock` (the system clock); the app tests' `FakeAppContainer`
  fixes it at `SampleProgram.FETCHED_AT`. The fake repositories in `core:testing`
  re-implement the Room rules; `RepositoryContract` (core:data tests) runs the
  same scenarios against both, so a changed rule gets a case there.
- **Data:** no invented sample data in the app.
- **Favourite cinemas:** `Cinema.isFavourite`, written only through
  `CinemaRepository.setFavourite` (detail's ☆/★ `IconToggleButton`, read as
  "Favourite, checked"); every cinema flow carries it. In the list,
  `CinemaFilter.FAVOURITES` is a filter, not a sort: only favourites, always in
  score order; the sort chips go back to `ALL` with their sort. Sort and filter live in the ViewModel (not persisted).
- **Seats** are identified by a stable `SeatId` stored in the layout (see
  `core/model/.../SeatLayout.kt`), never by grid position or label. Ids are never
  reused within a hall (`Hall.nextSeatId` is the hall's mark; the repository
  rejects layouts that reuse ids). Edit layouts only through
  `hall.startLayoutEdit()` (`LayoutEditSession`); derive labels with
  `layout.labels()`. A layout has at most `SeatLayout.MAX_ROWS` ×
  `MAX_COLUMNS` cells (50 × 60, the editor's limits) and `nextSeatId` ≤
  `MAX_SEAT_ID` (1,000,000,000), checked in `SeatLayout.init`; handing out an id at the
  limit throws `SeatIdsExhaustedException`, it never wraps. The editor catches only
  that one (message, edit refused); any other model exception is a bug.
- **Seat numbering** (`SeatLabels.kt`, spec §4.3) must match Yorck's booking.
  Every seat place is numbered, wheelchair spaces, companion seats and sofa
  places included. Each row has a `numberingDirection`: `LEFT_TO_RIGHT`
  (default) or `RIGHT_TO_LEFT`, as drawn with the screen at the top. Aisles
  split a row into blocks. `blockStartNumbers[b]` counts blocks from the left
  as stored, in both directions; its value is the number of that block's first
  numbered seat in numbering order (the rightmost seat when the row runs right
  to left). `CONTINUOUS` counts on across blocks, `RESTART_PER_BLOCK` restarts.
  When cell types change (aisles painted or removed, columns removed), a block
  start stays with the block that holds the seat it was set on and numbers
  that block's current first numbered seat (`LayoutRow.withCells`, applied by
  every `SeatLayout` operation that changes cell types): a split-off part gets
  none, merged blocks keep the start of the one numbered first, and when that
  seat is removed the start moves on to the next numbered seat with the number
  it had, so the remaining seats keep theirs (`LayoutEditSession` gives the
  original start back with the seat). A numbered seat added in front of a
  block with an explicit start takes the number before it (start − 1, counting
  back only to 1 and, in a continuous row, to just after the previous block's
  numbers; never above the start itself), so the existing seats keep their
  numbers. The editor paints a stroke in an order that keeps this
  (`LayoutDraft.strokeOrder`, `PaintStroke`), whichever way the finger moves.
  A row whose starts don't change keeps its stored list.
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
  Use `SeatLabel.displayText` / `shortText` / `description`, never build
  "Row …" texts yourself.
- **Rows without a name** (spec §4.3, e.g. Kant Kino Saal 3, every double sofa
  its own row): stored label `"-"` (`LayoutRow.NO_NAME`, `hasNoName`; Yorck's
  convention, kept in seed and JSON). `labels()` gives them no name:
  `rowLabels[i]` and `SeatLabel.row` are `null`, so texts read "Seat 12" /
  "Seat 12 · sofa", TalkBack "Seat 12, sofa", and the map draws no side label
  (numbers stay on the seats). They still count as seat rows for automatic row
  numbers. Don't confuse them with spacer rows (no seats, `rowLabels` `null`)
  or automatic rows (stored label `null`). `seatChoices` puts all their seats
  into one group (`label == null`, chip "Seats", by seat number); a hall with
  only such rows has no row step. The editor shows "-" (canvas and label
  field, "Unnamed row"); typing "-" makes a row unnamed.
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
  reach into row 0 (`hitTest`). Width and distance bounds live in
  `ScreenSizeRules` (core:model: width > 0 and ≤ 120 = 2 × `MAX_COLUMNS`,
  distance 0–15), shared by the editor's `ScreenRules`, the repositories and the
  backup validator. The editor sets distances of at least 0.5
  (`ScreenRules.MIN_DISTANCE`), so the bar's line stays half a row in front of
  row 0, whose touches `hitTest` keeps for painting; a stored 0 stays until the
  distance is changed. `LayoutDraft.start` snaps a stored width within the
  editor's range and a stored distance up to 15 to the 0.5 steps (a distance
  under 0.5 to 0 or 0.5); other widths and farther distances are kept, like an
  offset beyond the grid.
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
  Its size limits (texts and row labels within `TextLimits`, layouts within the
  `SeatLayout` limits and their JSON ≤ `LayoutJson.MAX_JSON_LENGTH` and not
  padded beyond twice their own encoding (`LayoutJson.isPadded`), seat ids below
  `MAX_SEAT_ID`, the screen within `ScreenSizeRules`) keep every row far below
  Android's 2 MB per row; a backup holds at most `DataLimits.MAX_HALLS` (1,000)
  halls, `createHall` refuses more (`HallLimitReachedException`, its message
  shown), and the seeder skips seed halls beyond it (a later seed version adds
  them once there is room). The repositories' writers enforce the same limits on
  every value they change (`updateHall` keeps a value the hall already has, e.g.
  one an older build stored, so it can't block a save), so nothing this version
  writes fails an export; for file paths they only cap the length, the stores create
  names that match the validator's file-name pattern. The forms cap their
  fields at or below the limits. All visits reads each hall's layout once
  (`VisitWithContext.hallLayout`), not once per visit.
  Imports validate everything before touching data (`BackupReader`; manifest
  and data must precede the files, unreferenced entries are skipped), check
  free space before staging each file and before writing the safety copy
  (`BackupLimits.minFreeBytes` stays free), make
  sure a safety copy exists (`filesDir/backups/pre-import-<seq>-<time>.zip`,
  ordered by sequence, not clock; reused if the newest already holds the
  current data; a failed import deletes the copy it wrote; pruned to the newest
  2 only after success; Settings lists both, newest first, and restores the
  chosen one through the same prepare → preview → commit path) and replace
  all rows in one transaction
  (`BackupRestorer`: imported files are added under names no existing file
  has, and removed again if the transaction fails). While
  `BackupRepository.isImporting` is true the UI blocks every other write
  (tabs, Back, Settings rows); Back is also blocked while an export runs (it
  runs in the Settings ViewModel, which Back would clear). Once the
  transaction has committed, nothing
  (progress callbacks, file clean-up, pruning, seeding, even Errors) can turn
  the import into a failure or delete its safety copy (`onCommitted`). Failures
  are `BackupException`s with user-facing messages. The layout JSON inside is
  versioned on its own (`LayoutJson.FORMAT_VERSION`, now 3): a new layout field
  bumps that, not `BACKUP_VERSION`, and an older app refuses such a backup as
  "can't be read by this app version". Tests:
  `core/data/src/test/.../backup/`. Known limitation: if the current data fails
  validation (e.g. a layout from a newer app), import is refused; there is no
  "Replace anyway". Data with more than `MAX_HALLS` halls (only possible from
  v1.0/v1.1, which had no cap on creating or importing halls) fails export and
  import that way too; the remedy is to delete halls first.
- **Row and seat labels:** Yorck mostly numbers rows and seats, which is the
  default labelling; some rows are letters ("C") or balcony rows ("R1"), and a
  row's first seat is often 2, 3 or 4. Keep fixtures, examples and UI texts
  numeric ("Row 7 · Seat 10") unless they show one of these cases.
- **App-private files:** reference screenshots (`ReferenceImageStore`,
  `files/reference/`) and visit photos (`VisitPhotoStore`, `files/photos/`) live
  in `core:data`. Entities store paths relative to `filesDir`; the stores ignore
  paths outside their folder and decode images subsampled (a reference
  screenshot to at most 2048 px, also one from a backup), `null` on out of
  memory. Files are copied in when picked; a replaced or
  removed file is deleted only after a successful save, discarding deletes the
  files added in that session, deleting a visit deletes its photo. The startup
  sweep (`DataContainer.sweepOrphanFiles`) deletes unreferenced visit photos
  (and camera captures) older than 7 days, because a visit form restored after
  process death still holds its unsaved photos, and unreferenced reference
  screenshots older than 1 h (the editor's draft doesn't survive process death).
  A restored visit form drops session photos whose files are gone
  (`VisitPhotoStore.exists`); if its photo was one, it clears it and says "The
  photo is no longer available." The saved visit's photo is never touched.
  Auto Backup excludes these files (database and preferences only); the backup
  export includes the referenced ones. Tests use the fakes in `core:testing`
  (`FakeReferenceImageStore`, `FakeVisitPhotoStore`, `FakeBackupRepository`).
- **Program / network (v1.1, spec §11.1 "Network & security", binding):**
  `core:program` is the only module with network code and the only manifest
  with `INTERNET`; inside it only the `net/` package (`HttpsProgramTransport`,
  `ProgramDownloader`). These checks fail the build otherwise:
  `NetworkConfinementTest` (core:program) scans every module's sources (all sets
  but `test`, `androidTest` and their `<Variant>` sets: no `java.net` except `URI`,
  `javax.net`, `URLConnection`, `openConnection`/`openStream`/`.toURL(`, WebView,
  MediaPlayer, VideoView, ExoPlayer/media3, `DownloadManager`, OkHttp, Retrofit,
  Ktor, …; outside build-logic also `loadLibrary`, `exec(`/`ProcessBuilder`,
  `dalvik.system`), manifests and resources of all sets (INTERNET, cleartext,
  `src="user"`, `debug-overrides`, network security configs outside
  `app/src/main/res/xml/`, WebView layouts), build files (`*.gradle(.kts)`,
  `*.toml`, build-logic: network, image, media, WebView, Firebase, Sentry, Play
  services libraries, file dependencies, `externalNativeBuild`, `srcDir(s)` and
  the variant API's `add…SourceDirectory`, repositories other than `google()`,
  `mavenCentral()` and `gradlePluginPortal()`; only
  `scripts/maven-central-mirror.init.gradle` may add Google's Maven Central
  mirror, and every module applies `cinex.android.application`/`.library`/
  `.feature` or `cinex.jvm.library`) and allows no `.jar`/`.aar`/`.so`/`.dex`/
  `.class` (any case) but the Gradle wrapper; those convention plugins refuse any
  dependency on files (`forbidFileDependencies` in build-logic, a reviewed list
  of plugin configurations excepted);
  `AppPermissionsTest` (app) pins the merged manifest's permissions, debug and
  release (the build passes both merged manifests to the unit tests);
  `:app:checkReleaseDependencyGroups` (runs before every build) allows release
  dependencies only from `androidx.*` (not webkit, media3, browser),
  `org.jetbrains*`, `org.jspecify` and `com.google.guava`; review a new group
  before adding it there (its denylist line carries `// network-scan: denylist`,
  the only line the build-file scan accepts; the task type,
  `CheckDependencyGroupsTask`, is in build-logic). `BuildLogicScanTest`
  (core:program) allows the marker on that line of `app/build.gradle.kts` only.
  The one request is `GET https://www.yorck.de/en/films`
  over `HttpsURLConnection`: no query, body or cookies (no `CookieHandler`); the
  app sends `User-Agent: Cinexperience/<version> (Android; personal app)` and
  `Accept`, the HTTP client adds `Host`, `Connection` and `Accept-Encoding: gzip`;
  at most 3 redirects, each only to `https://www.yorck.de/…`; 15 s connect, 30 s
  read; a watchdog aborts the connection (`TransportAbort` → `disconnect()`) when
  the download is cancelled and after 90 s in total, so switching off or deleting
  never waits for a timeout; at most 10 MB, counted while streaming; only HTTP 200.
  Nothing reaches the transport unless `DownloadPermission.ALLOWED` (asked again
  before every request); downloads happen only in `refreshIfStale()` (no
  program, older than 6 h or from the future) and `refresh()`, never in the
  background, one at a time. Commands (`setDownloadsEnabled`, `allowDownloads`,
  `deleteProgram`) run to the end even if the caller is cancelled (off is stored
  first, then the download stopped) and return a `ProgramActionResult`;
  `ProgramStatus` is for downloads only (`dismissError()`). One
  `ProgramRepository` per process (`AppContainer`). The page is untrusted:
  `ProgramPageParser` cuts out the `__NEXT_DATA__` block by text search, bounds
  it (500,000 JSON values, depth 64), decodes only the used fields into its own
  types (unknown parts are skipped, not built; if that typed decoding fails, e.g.
  for an entry or its `fields` that isn't an object, it decodes again with the two
  lists as trees and skips the entries that fail) and checks every field
  (`ProgramRules`; the model types' `init` blocks check again); texts are cleaned
  plain text (`ProgramText`), never HTML. Films and specials are keyed by
  `FilmKey` (kind + slug); a showing a film and specials both list belongs to the
  film and names the specials (`Showing.specialKeys`, `Program.showingsOf(key)`).
  Start times are Berlin wall-clock: only `+01:00`, `+02:00` or no offset is
  accepted (Yorck writes `+01:00` all year), anything else makes the showing
  invalid; clock changes follow `ProgramTime`. SV is shown as "SV" until its
  meaning is confirmed. Links only from `ProgramLinks` (fixed patterns, checked
  ID/slug), opened in the browser. The cache `files/program/program.json` (own
  format, `ProgramCache.FORMAT_VERSION`, at most 4 MB, atomic replace,
  unreadable = none and deleted) is not in the database, the backup or Auto
  Backup, and the startup sweep never touches it.
  `app/src/main/res/xml/network_security_config.xml`: HTTPS only, system CAs only,
  no pinning. No WebView, no images from Yorck. Download failures become
  `ProgramStatus.Failed` with a user-facing message (Errors such as out of memory
  too); the last good program stays. Tests: `FakeTransport` (no sockets),
  `FakeHttpsConnection` (a fake `HttpsURLConnection`; `HttpsProgramTransportTest`
  checks the real connection setup with it) and the invented fixture
  `core/program/src/test/resources/program/films-page.html`;
  app tests pass `DefaultProgramRepository.create(…, transport = …)` a transport
  that always fails. Never commit Yorck's page or parts of it; `RealProgramPageTest`
  checks a local copy named by `CINEX_PROGRAM_PAGE` (skipped otherwise; keep it in
  the git-ignored `yorck-pages/` or as `*.yorck.html`). UI tests use
  `FakeProgramRepository`, `buildProgram` and `SampleProgram` (core:testing).
- **Program UI** (`feature:program`, spec §6.9): the tab asks for consent first
  (`ProgramMode`); opening it calls `refreshIfStale()`, pull to refresh and the
  refresh button `refresh()`; "Now showing" (cinema detail, `Program.nowShowing`),
  the film page and Settings never download. The tab shows download errors
  (`ProgramStatus`, dismissable); Settings shows its commands' `ProgramActionResult`.
  Showings join the app's cinemas by seed key (`CinemaJoin`: score, ★, the hall of
  one-hall cinemas). Day/time/version/search logic is pure (`ProgramFilters`,
  Berlin dates via `ProgramTime`); the title search also matches a showing's
  specials. The filter and saved filters are app settings in `program_filters.xml`
  (Auto Backup, not the backup file). Model lookups (film by `FilmKey`, a film's
  showings via `showingsOf`, search titles, `endsAbout`) go through `ProgramLookup`.
  Links come only from `ProgramLinks` and open in the browser (`ACTION_VIEW`, only
  `https://www.yorck.de/`; no browser = a message). Texts from Yorck's page joined
  with other text go through `isolate()` (Unicode isolates), so right-to-left text
  can't reorder times or names.
- **Ticket PDF** (`core:ticket`, spec §11.2, binding): the file is untrusted. The
  reader handles only Yorck's shape (Qt 5.5: classic cross-reference table, found
  through it, never by scanning; Flate content; Type 0 / Identity-H fonts with
  `/ToUnicode`; DCT images) and refuses anything else with a `TicketPdfError`;
  every limit is in `TicketPdfLimits` (2 MB file, 4 pages, 2,000 objects, 2 MB per
  unpacked stream and 8 MB together, nesting, glyphs, ≤ 512 px QR candidates) and
  has a test. It never follows links, actions or external data; `read()` never
  throws for any file content (`content()` is the strict path the fuzz tests use). Fields are checked by
  `TicketPdfRules` and cleaned like the program's texts (`TicketText`); each is
  `Found` or `NotFound(MISSING/INVALID/AMBIGUOUS)`; several seats only where
  unambiguous. Never read or returned: the "Gebucht von" name, prices, numbers,
  address, conditions. QR: only a grid whose finder, separator, timing and
  alignment patterns, dark module and both format copies check out and whose
  pattern centres lie on the grid is returned; no decoding, no generation. Tests
  use invented fixtures (`InventedTicketPdf`, `TestQrCodes`, the JDK's JPEG codec
  in plain JVM tests). Never commit a real ticket; `RealTicketTest` reads one
  named by `CINEX_TICKET_PDF` (skipped otherwise; keep it in the git-ignored
  `tickets/` or as `*.ticket.pdf`) and asserts and prints structural facts only.
- **Seed data:** `core/data/src/main/assets/seed/yorck.json`. Bump `seedVersion`
  for every change; `seedKey`s are permanent. Seeding only inserts missing items
  and fills empty fields, it never overwrites or deletes (a renamed seed hall
  only gets the new name on fresh installs). An item the user deleted (its seed
  key has `seed_snapshot` rows, which are kept) is not inserted again, nor are
  the halls of a deleted cinema. A user-added item with a seed item's name is
  adopted (it gets the seed key, stays deletable), and halls the seeder adds to
  a cinema the user created stay deletable too. For `screenOffset`, 0 counts as
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
  they still hold what the seeder last wrote into the hall (snapshot fields
  `appliedScreenWidth`/`appliedScreenDistance`, like `appliedLayout`; halls
  seeded before them fall back to the previous seed's values); a value the
  user changed is kept. The screen offset is touched only when the new
  layout is narrower: it is then snapped with
  `ScreenOffsetRules.snap(offset, newColumns)`, so the screen stays over the
  grid. Names, notes and the reference screenshot are never touched. A hall
  whose seat id mark leaves no room below `MAX_SEAT_ID`
  for the seed layout's ids (only damaged or crafted data) keeps its layout
  (or stays without one) and its `appliedLayout` stays as it was, so later seed
  versions try again (an empty layout, like any fill, only with a changed seed
  layout).
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
  unchanged; halls without a screen get columns × 1.5, a screen as wide as the
  old grid follows the new one unless it was measured (delphi LUX),
  `test_screen_width.py`) and
  `core/data/src/test/resources/seed/yorck-seat-numbers.json` (Yorck's row
  label, seat text and kind of every seat). Where Yorck's kinds are wrong, the
  tool's `KIND_OVERRIDES` (seedKey → kind of every place, with the reason) sets
  them in the seed only; the fixture keeps Yorck's kinds and `YorckSeedTest`
  lists the hall in `kindCorrections` (now: Kant Kino Saal 3, double sofas →
  `F`). A new hall needs a seed entry and a
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
