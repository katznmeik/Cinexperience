# Cinexperience — Product Specification

**Status:** Draft v1.0 (agreed in planning, no development started)
**Owner:** katznmeik
**Last updated:** 2026-10-14 (rev. 20)

---

## 1. Summary

Cinexperience is a personal Android app for rating the cinemas of the Berlin
chain **Yorck Kinos** (https://www.yorck.de/en) — down to each individual hall —
and for finding out **which row and seat give the best experience** in every hall.

Halls differ a lot in size, sound and picture quality. By logging every visit
(where you sat, how it was), the app builds up a personal picture of which
cinema, which hall and which seat to book next time.

## 2. Goals & non-goals

### Goals (v1)
- Browse all Yorck cinemas and their halls.
- Draw each hall's seat layout in a simple grid editor.
- Log visits with ratings, seat, note, photo and screening format.
- See a colour-coded seat map per hall (green / yellow / red) plus a favourite seat.
- Rate cinemas on cinema-level categories.
- Rank halls / cinemas by any category.
- Export / import all data as a backup file.

### Non-goals (v1)
- Multiple users, accounts, login, cloud sync or a backend server.
- Publishing on Google Play (installed directly via APK).
- Linking to the Yorck customer account (no public API; see §11).
- Showtimes, ticket booking, film information lookups.
- Cinemas outside the Yorck chain (data model keeps this possible later).
- Languages other than English.

## 3. Target user & device

- **User:** the owner only.
- **Device:** Samsung Galaxy S25, One UI 8.5, Android 16.
- **Supported Android versions:** minSdk 34 (Android 14), targetSdk 36 (Android 16);
  compiled against API 37 (required by current AndroidX libraries; no runtime effect).
- **Orientation:** portrait.
- **Connectivity:** fully offline. No network permission needed in v1.

## 4. Domain concepts & data model

All data is stored locally in a Room (SQLite) database.

### 4.1 Cinema
| Field | Type | Notes |
|---|---|---|
| id | Long | |
| name | String | e.g. "Delphi LUX" |
| address | String? | |
| notes | String? | free text |
| isPreloaded | Boolean | shipped with the app vs. user-added |
| seedKey | String? | stable key of a preloaded cinema (seed updates) |
| isFavourite | Boolean | marked as a favourite cinema by the user (§6.1, §6.2) |

### 4.2 Hall
| Field | Type | Notes |
|---|---|---|
| id | Long | |
| cinemaId | Long | FK → Cinema |
| name | String | e.g. "Saal 1" |
| layout | SeatLayout? | null until drawn by the user |
| screenWidth | Float? | screen width, in seat-widths (grid columns); adjustable |
| screenDistance | Float? | gap between screen and first row, in row-depths; adjustable |
| screenOffset | Float | horizontal shift of the screen's centre from the grid's centre, in seat-widths (0.5 steps; negative = to the left, as seen from the seats facing the screen); 0 = centred; adjustable. Yorck's plans don't show it, so it is set by hand |
| referenceImagePath | String? | screenshot of the hall plan from the Yorck app (see §6.5); app-private relative path |
| isPreloaded, seedKey | | as for Cinema |
| notes | String? | |

### 4.3 SeatLayout (grid)
A rectangular grid of `rows × columns` cells. Each cell is one of:
- `SEAT` — a bookable seat, with a row label and seat number. A seat has a
  **kind**: standard, wheelchair space, companion seat (Begleitsitz) or sofa
  place. Non-standard kinds are drawn with a small icon (as in the Yorck plans)
  and can be rated like any other seat.
- `GAP` — empty space (e.g. short rows, irregular shapes)
- `AISLE` — walkway (visually distinct from a gap)

Row labels and seat numbers are auto-generated (row 1…n, seat 1…m per row,
skipping gaps/aisles) and can be edited to match the cinema's real labels.
**Seat numbers must match Yorck's booking exactly.** Per row, numbering runs
either **left to right** or **right to left** as drawn (screen at the top);
Yorck uses both, even within one cinema. Every seat place is numbered,
including wheelchair spaces, companion seats and sofa places, as Yorck does.
A row's first number can be any number (Yorck rows often start at 2, 3 or 4).
For the rare seat whose Yorck label is not a plain number (e.g. a wheelchair
space labelled "R"), a seat can carry a fixed label that replaces its number.
Row labels can be numbers or letters (e.g. "R1" for balcony rows). A row can
also have no name ("-" in Yorck's data, e.g. Kant Kino Saal 3, where every
double sofa is its own unnamed row): its seats are then shown by seat alone
("Seat 12 · sofa"), and the seat picker lists them by seat number.

**Split rows:** a row interrupted by an aisle forms separate *blocks*
(e.g. left / centre / right). Per row, the user chooses whether seat numbering
runs continuously across blocks or restarts in each block, and can set the
starting number of each block. A row can also be shifted by half a seat
(staggered rows), as seen in some halls.

The screen is drawn at the top as a straight bar, with the width, distance and
horizontal offset from §4.2.

**Seat identity:** every seat cell carries a stable `seatId` that is never
reused. Visits and overrides refer to seats by this id, so they survive
relabelling, renumbering and inserting rows; labels ("G 10") are derived from
the layout when displayed. A seat that is deleted and redrawn gets a new id.

### 4.4 Visit
| Field | Type | Notes |
|---|---|---|
| id | Long | |
| hallId | Long | FK → Hall |
| date | LocalDate | defaults to today |
| filmTitle | String? | |
| seatId | Int? | the seat sat in (§4.3 seat identity); picked on the seat map |
| screeningFormat | Enum? | OV, OmU (OV + German subs), German dub, 3D, 70mm, Other |
| hallRatings | 6 × Int? (1–5) | sound, picture, screen size, seat comfort, legroom, seating steepness |
| seatRating | Int? (1–5) | "How was this seat?" |
| note | String? | free text |
| photoPath | String? | photo stored in app-private storage (relative path) |

**All ratings are optional.**

### 4.5 CinemaRating
Cinema-level categories, rated separately from halls (1–5, optional):
- Atmosphere
- Bar / snacks
- Popcorn quality
- Seat comfort (cinema-wide impression, independent of the per-hall rating)

Stored per visit (the visit's hall → cinema) so they average over time like hall
ratings. Shown as an optional section in the "Add visit" form.

### 4.6 SeatOverride
| Field | Type | Notes |
|---|---|---|
| hallId, seatId | | identifies the seat |
| colour | GREEN / YELLOW / RED / null | manual override; null = automatic |
| isFavourite | Boolean | max. one favourite per hall |

## 5. Rating logic

- **Hall category score** = mean of all non-empty values for that category
  across the hall's visits. Shown with one decimal and the number of ratings.
- **Hall overall score** = mean of the six category scores that have data.
- **Cinema category score** = mean across all visits to that cinema.
- **Cinema overall score** = mean of (cinema category scores + hall overall
  scores of its halls), so both the venue and the halls count.
- **Unrated** values are shown as "–", never as 0.

### Seat colour
For each seat:
1. If a manual override exists → use it.
2. Else, if the seat has one or more visit seat ratings → average:
   - ≥ 4.0 → **green**
   - ≥ 2.5 and < 4.0 → **yellow**
   - < 2.5 → **red**
3. Else → neutral / grey (no data).

The favourite seat is shown with a ⭐ on top of its colour.

## 6. Screens & navigation

Bottom navigation with three tabs: **Cinemas**, **Rankings**, **Settings**.

### 6.1 Cinemas (home)
- List of all cinemas: name, overall score, number of visits.
- Sort: score (default; unrated last) / name / most visited. A fourth chip,
  **Favourites**, shows only the favourite cinemas (in score order); favourite
  cinemas carry a ★ marker in the list.
- FAB "Add visit" (quick entry: pick cinema → hall).

### 6.2 Cinema detail
- Header: name, address, overall score, cinema category scores, and a ☆/★
  toggle to mark the cinema as a favourite.
- List of halls with overall score and a ⭐ marker if a favourite seat is set.
- Actions: edit cinema, add hall.

### 6.3 Hall detail
- **Seat map** (zoomable/pannable) coloured per §5, every numbered seat showing
  its seat number, with a legend (Great · OK ·
  Avoid · No data · Favourite). Tap a seat → an inline **seat card** below the map:
  average seat rating, visits in that seat, approximate viewing angle to the
  screen (derived from screen width, distance and offset), colour override (Auto / Great /
  OK / Avoid), mark/remove favourite.
  The map may be as tall as the window between the app's bars (window height
  minus 200 dp, at least 520 dp); a hall still taller than that at the
  width-fitted zoom (e.g. Kant Kino Saal 3) starts fitted whole, smaller and
  centred, so every row is visible without panning; zoom works as before.
- **Score breakdown:** six categories with bars and rating counts.
- **Visit history:** newest first; tap to open/edit a visit.
- Button **"Add visit"** (hall pre-selected).
- If no layout yet: prompt "Draw seat layout" → layout editor.

### 6.4 Add / edit visit
Single scrolling form:
1. Cinema & hall (pre-filled when opened from a hall)
2. Date, film title, screening format
3. Seat — picked by tapping the seat map, which shows the seat numbers (or skipped)
4. Seat rating
5. Hall ratings (six star rows)
6. Cinema ratings (collapsible)
7. Note, photo (camera or gallery)

Save is always allowed — nothing besides hall is required.

### 6.5 Layout editor
- **Reference screenshot:** attach a screenshot of the hall's seat plan from the
  Yorck app. It is shown semi-transparently behind the grid (adjustable opacity,
  zoom and position), so the layout can be traced exactly — including split and
  staggered rows. The screenshot is kept with the hall for later comparison;
  its alignment (position, zoom, opacity) applies to the current editing
  session only.
- **Seed layouts from screenshots:** screenshots supplied by the owner during
  development are transcribed into preloaded layouts (see §9), so those halls
  work out of the box.
- Set number of rows and columns (added/removed at the end; rows can also be
  inserted above/below the selected row or deleted).
- **Paint tool:** choose Seat / Gap / Aisle (and for seats a type: standard,
  wheelchair, companion, sofa), then tap or drag across cells to paint them.
  Two fingers zoom and pan. Undo/redo for every change.
- Edit row labels and starting seat numbers; per row: numbering direction
  (left to right / right to left), block numbering (continuous / restart per
  block) and half-seat offset.
- **Seat numbers** are shown on every numbered seat and update live while
  painting or renumbering.
- **Screen:** tap the screen bar to select it; the selected screen is
  highlighted in green (no extra box). Drag its handles to change its width,
  drag the bar up/down to change its distance to the first row and sideways to
  move it left or right: the screen is not always centred over the seats, and
  this changes the viewing angles a lot. Steppers for width, distance and
  position are available as well.
- Preview with screen at the top; save / discard.
- Changing a layout that already has visits warns if seats with data would
  disappear.

### 6.6 Rankings
- Choose scope: **Halls** or **Cinemas**.
- Choose category (overall or any single category).
- Sorted list, highest first; items with no data at the bottom.

### 6.7 Settings
- **Backup:** Export backup (shows date and contents of the last export),
  Import backup (validates, previews, warns that it replaces all data), Restore safety copy, Android Auto Backup shown as "On" (§8)
- **Data:** Manage cinemas & halls (add, rename, delete user-added ones; shows
  counts and how many layouts are drawn); All visits (§6.8)
- **About:** version and build ID (the short commit hash the APK was built
  from; it matches the APK file name from CI), note that the app is unofficial
  and not affiliated with Yorck Kinos

### 6.8 All visits
- One chronological list of every visit across all cinemas, newest first.
- Each entry: film, date, cinema · hall, seat, screening format, seat rating.
- Tap to open/edit the visit (§6.4).
- Reached from Settings → Data → All visits.

## 7. Look & feel

- **Dark theme only** in v1 (comfortable to use in a dark auditorium).
- Visual style loosely inspired by Yorck's branding: near-black background,
  bold uppercase display type, **black-and-white only** — the seat colours
  (green / yellow / red) are the only colours in the UI, so the seat map stands out
  (the one exception: the layout editor highlights the selected screen in green). No Yorck logos or trademarks are
  used in the app.
- Mockups of all v1 screens: https://claude.ai/artifact/24gq7hLsHPUoABFm2Ro8Q9
- Seats to avoid carry a × mark; the favourite seat carries a ⭐. Seats without
  data have a subtle light rim so they stay visible (≥3:1 contrast).
- Seat numbers on the seat map and in the editor are legible on every seat
  colour (≥4.5:1 where the seat is large enough to hold them).
- The screen is drawn as a **straight bar**: no Yorck screen is curved.
- **Transitions** between screens are short and consistent: no long fade-out
  and no hard cut at the end, also with Android's predictive back gesture.
- **App icon:** a curved screen above a block of seats, light on near-black,
  with a monochrome layer for Android themed icons; the splash screen shows the
  same mark on the dark background.
- Seat colours must be distinguishable in the dark theme and also carry a
  secondary cue (icon/pattern) for colour-blind readability.
- Material 3 components via Jetpack Compose.

## 8. Backup & restore

- **Export:** writes one `.zip` file with `manifest.json` (format, `backupVersion`,
  app version, export date, counts), `data.json` (every cinema, hall incl.
  layout, visit, seat override and the seed state, with their ids) and the
  `photos/` and `reference/` files those rows refer to. Saved through the
  Android file picker (e.g. Google Drive, Downloads). Settings shows the date
  and counts of the last export.
- **Import:** pick a backup file → the whole file is validated before any data
  is touched (zip safety and size limits, format and version, data
  consistency, every layout readable, every referenced file present) →
  preview (export date, counts) → confirm → all current data is replaced in
  one step. If anything fails, the previous data and files stay untouched.
- **Safety copy:** right before an import replaces the data, the current data is
  saved as an internal backup (the latest 2 are kept) and can be restored from
  Settings ("Restore safety copy").
- `backupVersion` lets later app versions migrate older backups; backups from a
  newer app version are rejected with a clear message.
- **Android Auto Backup** covers the database and settings (not photos or
  screenshots) as an additional safety net; Settings shows it as "On".

## 9. Preloaded data

The app ships with a seed list of Yorck cinemas and their halls
(`core/data/src/main/assets/seed/yorck.json`). Hall names are generic
("Saal 1", "Saal 2", …) unless the real name is known (delphi LUX "Luxbox",
Yorck "Yorck"/"New Yorck"), and can be renamed in the app.

**Preloaded layouts (M8):** every hall of all 14 cinemas (38 halls), built from
the seat plans on yorck.de's seat selection pages (October 2026, owner-approved,
no seat was ever selected): seat positions, seat types (wheelchair, companion,
sofa, permanently blocked seats count as normal seats), row labels and seat
numbers exactly as Yorck has them. One documented correction: Kant Kino Saal 3
is a room of 11 double sofas along a centre aisle (Yorck's data lists them as
normal seats), so its 22 places are sofa places. Halls with a stalls and a balcony area
("Parkett"/"Rang") show the balcony rows behind the stalls with Yorck's labels
(e.g. "R1"). yorck.de doesn't show the real screen (its data always makes the
screen exactly as wide as the seating), so a new hall's screen starts as wide as
its seating, 1.5 rows in front of the first row and centred; the delphi LUX
halls keep the width and distance measured from the Yorck app. Width, distance
and offset (§4.2) are set in the editor. The delphi LUX layouts that were transcribed from the
owner's Yorck-app screenshots in M2 are replaced by the ones from yorck.de.

Verified in M8 against yorck.de (14 cinemas, 38 halls; where yorck.de names a hall differently, its name is in brackets):

| Cinema | Address | Halls |
|---|---|---|
| Babylon Kreuzberg | Dresdener Straße 126 | 2 (A, B) |
| Blauer Stern | Hermann-Hesse-Straße 11, 13156 Berlin | 2 |
| Capitol Dahlem | Thielallee 36 | 1 (A) |
| Cinema Paris | Kurfürstendamm 211 | 1 (CP) |
| Delphi Filmpalast | Kantstraße 12a | 1 |
| delphi LUX | Kantstraße 10 | 7 (Saal 1–6, Luxbox) |
| Filmtheater am Friedrichshain | Bötzowstraße 1–5 | 5 |
| International (yorck.de: Kino International) | Karl-Marx-Allee 33 | 1 |
| Kant Kino | Kantstraße 54 | 5 |
| Neues Off | Hermannstraße 20 | 1 (Kino 1) |
| Odeon | Hauptstraße 116 | 1 |
| Passage | Karl-Marx-Straße 131 | 4 |
| Rollberg | Rollbergstraße 70 | 5 |
| Yorck | Yorckstraße 86 | 2 ("Yorck", "New Yorck") |

**Seed updates:** the seed file has a `seedVersion`; each cinema and hall has a
stable `seedKey`. When a newer seed version ships, missing cinemas/halls are
added and still-empty fields (address, layout, screen geometry) are filled in;
names, notes and anything the user has set are never overwritten, and nothing
is deleted. One exception: a layout that is still exactly the one an older seed
supplied (the user never edited it) is replaced by the newer seed's layout,
together with its screen width and distance, if the hall has no visits and no
seat colour overrides or favourite; otherwise it is kept, and the user can
redraw it.

Users can add, rename or delete cinemas and halls at any time (preloaded ones
can be renamed but not deleted).

## 10. Technical approach

| Area | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3 |
| Architecture | Single-activity, MVVM, unidirectional data flow |
| Persistence | Room + Kotlin Flow |
| DI | Manual DI (small app container) |
| Navigation | Navigation Compose |
| Images | Coil; photos copied into app-private storage |
| Serialization | kotlinx.serialization (backup JSON) |
| Seat map | Custom Compose `Canvas` with zoom/pan |
| Build | Gradle (Kotlin DSL), version catalog |
| Testing | Unit tests for rating logic & backup; Compose UI tests for key flows |
| Distribution | Signed APK, sideloaded (see §14) |
| CI | GitHub Actions: build, tests, screenshots (see §14) |

## 11. Future: ticket import (v1.1)

Linking directly to a Yorck customer account is **not planned**: there is no
public API, and it would require storing the Yorck password and relying on
undocumented endpoints that can break at any time.

Instead, v1.1 adds **ticket import via Android's Share menu**:
- Share a booking-confirmation email or ticket PDF to Cinexperience.
- The app extracts cinema, hall, date/time, film, row and seat.
- Opens a pre-filled "Add visit" form; the user adds ratings.
- Unrecognised fields are left empty for manual entry.

**Prerequisite:** one or two real sample tickets/emails (personal data
redacted) to build and test the parser.

## 12. Milestones

| # | Milestone | Content |
|---|---|---|
| M0 | Project setup | Android project, theme, navigation skeleton, GitHub Actions CI (APK + tests + screenshots), fixed signing key |
| M1 | Data foundation | Room schema, verified seed data, cinema & hall CRUD |
| M2 | Layout editor | Grid editor incl. split/staggered rows, screen size & distance, reference screenshot overlay, seat map rendering, seed layouts from screenshots |
| M3 | Visit logging | Add/edit visit form incl. seat picker, photo, format |
| M4 | Scores & seat map | Rating aggregation, seat colours, overrides, favourite |
| M5 | Rankings | Sortable rankings for halls and cinemas |
| M6 | Backup | Export / import zip, Auto Backup |
| M7 | Polish & release prep | Accessibility pass, app icon, signed release APK, release workflow |
| M8 | Polish from testing | Fixes and improvements from the owner's tests on the phone, delivered in batches → v1.0 release |
| M9 | Ticket import | Share-intent parser (v1.1) |

## 13. Development approach

The code is split into Gradle modules with clear boundaries, so work can be
divided — and partly parallelised between multiple AI agents — by module and
complexity:

| Module | Content | Complexity |
|---|---|---|
| `core:model` | Domain classes, rating & seat-colour logic | Medium — fully unit-tested |
| `core:data` | Room DB, repositories, seed data | Medium |
| `core:ui` | Theme, shared components (star rating, cards) | Low |
| `core:seatmapui` | Reusable seat map (drawing, zoom/pan, hit-testing, accessible seat picker), shared by hall detail and the visit form | High |
| `feature:seatmap` | Hall detail, seat map canvas, layout editor, screenshot overlay | **High** |
| `feature:cinemas` | Cinema list & detail | Low–medium |
| `feature:visits` | Add/edit visit form, photo handling | Medium |
| `feature:rankings` | Rankings screen | Low |
| `feature:backup` | Export / import, schema migration | Medium |
| `feature:ticketimport` | Share-intent parser (v1.1) | High |

Working rules:
- **Sequential first:** M0 (project setup) and `core:model` / `core:data` are
  built first by a single agent, because every feature depends on them.
- **Parallel afterwards:** once the core is in place, independent feature
  modules (e.g. `seatmap`, `rankings`, `backup`) can be built in parallel, each
  on its own branch, against the shared interfaces.
- **Effort by complexity:** high-complexity modules (seat map/editor, ticket
  parser) get the most careful design, review and tests; simple screens are
  handled in lighter passes.
- **Integration & review:** every module is merged only after building, passing
  its tests and a review pass. One integration step at the end of each
  milestone checks the app as a whole.

### 13.1 Agent orchestration & effort levels

Claude (this session) acts as **orchestrator**: it briefs each dev agent with a
task from this spec, reviews and tests the result, and merges it. Every agent
runs on **Opus 5.5** with an effort level matched to the task's risk and
complexity (higher effort = more careful reasoning, but more time and usage):

| Task | Effort | Reason |
|---|---|---|
| M0: project setup, CI, signing | medium | Mostly standard setup |
| `core:model`: rating & seat-colour logic | high | Small, but every score depends on it; fully tested |
| `core:data`: Room DB, seed data | high | Schema is hard to change later; backups depend on it |
| `core:ui`: theme, shared components | medium | Follows the mockups |
| `feature:cinemas`: lists & detail screens | medium | Standard screens |
| `feature:visits`: visit form, photos | medium | Form plus photo storage |
| `feature:rankings` | low | Sorting a list |
| `feature:seatmap`: seat map & layout editor | xhigh | Hardest part: zoom/pan, split & staggered rows, overlay, screen geometry |
| `feature:backup`: export / import | high | Errors mean data loss |
| Seed layouts from Yorck-app screenshots | high | Seats must be transcribed exactly |
| M8 polish items | medium (high when data, backup or the seat map change) | Small changes, but existing data must stay intact |
| `feature:ticketimport` parser (v1.1) | xhigh | Error-prone free-text parsing |
| Review agents | high (xhigh for seat map & backup) | Independent check before merge |
| Stubborn bugs | max | Only when needed |

At most **two agents build in parallel**, so builds don't slow each other down
on the cloud machine.

### 13.2 Branches & pull requests

- `claude/cool-curie-fx1oko` is the **integration branch** (holds this spec).
- Each milestone or module is developed on its **own branch**
  (e.g. `claude/m0-setup`, `claude/seatmap`) and delivered as a pull request
  into the integration branch.
- A PR is merged only when CI is green and the owner has reviewed it (§14).

## 14. Build & verification

### 14.1 Flow
1. Each milestone is developed on a branch and delivered as a pull request.
2. **GitHub Actions** runs on every push and:
   - builds the signed APK and attaches it to the run as a downloadable artifact;
   - runs all unit tests;
   - renders **screenshots of every screen** (Compose screenshot tests,
     e.g. Roborazzi) and attaches them to the run / PR.
3. The owner reviews screenshots, tests the APK on the PC emulator and/or
   phone, and gives feedback in the PR or chat.
4. The PR is merged once the owner is satisfied and CI is green.

The developer (Claude) builds and runs tests in the cloud before every push,
but cannot run an emulator there — hands-on verification is done by the owner.

### 14.2 Windows PC (emulator)
- **Requirements:** 16 GB RAM recommended (8 GB minimum), ~20 GB free disk,
  hardware virtualisation enabled.
- **One-time setup:** install Android Studio → Device Manager → create a
  virtual device (e.g. Pixel 9) with the **Android 16** system image.
- **Testing without code:** download the APK from the CI run and drag it onto
  the emulator window.
- **Testing with code:** open the repository in Android Studio and press Run;
  tests can be run locally too.
- Windows Subsystem for Android is discontinued and not used.

### 14.3 Galaxy S25
- **APK install (default):** download the APK on the phone (or copy from PC).
  - Temporarily disable **Auto Blocker**
    (Settings → Security and privacy → Auto Blocker).
  - Allow "Install unknown apps" for the browser / My Files.
  - On a Play Protect warning: More details → Install anyway.
- **Optional:** Developer options → Wireless debugging, paired with
  Android Studio, to install directly from the IDE.

### 14.4 Signing & data safety
- One fixed signing key is created in M0 and stored as a **GitHub Actions
  secret** (never committed). All builds use it, so every new APK installs as
  an update over the previous one and **keeps existing data**.
- Before installing a new version once real data exists, make an **Export**
  backup (§8) as a precaution.

### 14.5 Review routine per milestone
| Step | Where | Time |
|---|---|---|
| Check screenshots | Browser (PR) | ~2 min |
| Click through new features | Emulator on PC | ~10–15 min |
| Real-world use | Galaxy S25 | as desired |
| Feedback | PR comment or chat | – |

## 15. Open items

- Verify the current list of Yorck cinemas and hall names (M1).
- Sample ticket email/PDF for v1.1 parser.
- Yorck-app screenshots of hall seat plans (for preloaded layouts).
