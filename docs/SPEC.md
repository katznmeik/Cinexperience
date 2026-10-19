# Cinexperience — Product Specification

**Status:** v1.3.0 released (2026-10-17); v1.4.0 (ticket import, ticket QR code, reminders) built, release pending
**Owner:** katznmeik
**Last updated:** 2026-10-19 (rev. 36)

---

## 1. Summary

Cinexperience is a personal Android app for rating the cinemas of the Berlin
chain **Yorck Kinos** (https://www.yorck.de/en) — down to each individual hall —
and for finding out **which row and seat give the best experience** in every hall.

Halls differ a lot in size, sound and picture quality. By logging every visit
(where you sat, how it was), the app builds up a personal picture of which
cinema, which hall and which seat to book next time.

From v1.1 the app also shows Yorck's current program with the filters the Yorck
app and website lack (e.g. "this week at delphi LUX, starting 16–17 o'clock").

From v1.4 a Yorck ticket PDF can be shared to the app: it fills in the visit
(cinema, hall, seat, date and time, film), shows the ticket's QR code for the
entrance, and reminds before the showing and to rate the visit afterwards.

## 2. Goals & non-goals

### Goals (v1)
- Browse all Yorck cinemas and their halls.
- Draw each hall's seat layout in a simple grid editor.
- Log visits with ratings, seat, note, photo and screening format.
- See a colour-coded seat map per hall (green / yellow / red) plus a favourite seat.
- Rate cinemas on cinema-level categories.
- Rank halls / cinemas by any category.
- Export / import all data as a backup file.

### Goals (v1.1)
- Show Yorck's current program (films and showings of all Yorck cinemas) with
  filters by cinema, day, start time, version and film (§11.1).
- Link each showing to its seat selection on yorck.de, and log a visit from a
  showing.

### Goals (v1.4)
- Import a Yorck ticket PDF from the Share menu into an upcoming visit (§11.2).
- Show the ticket's QR code for the entrance, offline and sharp (§6.11).
- Remind before the showing and to rate the visit afterwards (§11.3).

### Non-goals (v1)
- Multiple users, accounts, login, cloud sync or a backend server.
- Publishing on Google Play (installed directly via APK).
- Linking to the Yorck customer account (no public API; see §11.2).
- Showtimes, ticket booking, film information lookups (v1.1 adds Yorck's
  program, §11.1; booking stays on yorck.de).
- Cinemas outside the Yorck chain (data model keeps this possible later).
- Languages other than English.

## 3. Target user & device

- **User:** the owner only.
- **Device:** Samsung Galaxy S25, One UI 8.5, Android 16.
- **Supported Android versions:** minSdk 34 (Android 14), targetSdk 36 (Android 16);
  compiled against API 37 (required by current AndroidX libraries; no runtime effect).
- **Orientation:** portrait.
- **Connectivity:** v1 is fully offline, with no network permission. From
  v1.1 the app is offline except for the Program: after the owner agrees on
  first use, it downloads Yorck's public program page from www.yorck.de, and
  nothing else (§11.1, Network & security).

## 4. Domain concepts & data model

All data is stored locally in a Room (SQLite) database.

### 4.1 Cinema
| Field | Type | Notes |
|---|---|---|
| id | Long | |
| name | String | e.g. "Delphi LUX" |
| address | String? | |
| notes | String? | free text |
| isPreloaded | Boolean | inserted from the app's seed data (§9) vs. user-added; preloaded items can't be deleted. A user-added cinema the seed adopts (same name), and the seed halls added to it, stay user-added |
| seedKey | String? | stable key of the cinema's seed entry (seed updates); also set on a user-added cinema the seed adopted |
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
starting number of each block. When aisles are painted or removed or columns
are removed, a block's start number stays with the block that holds the seat
it was set on. When that seat is removed, the start moves on to the next seat
with that seat's number, so the remaining seats keep their numbers. A numbered
seat added in front of a block with an explicit start takes the number before
it (start − 1), so the existing seats keep theirs. New seats count back only to
1 and, in a continuous row, to just after the previous block's numbers; where
that stops them, the block starts there (never above its old start) and the
seats behind move up as far as needed. A row can also be shifted by half a
seat (staggered rows), as seen in some halls.

The screen is drawn at the top as a straight bar, with the width, distance and
horizontal offset from §4.2.

**Seat identity:** every seat cell carries a stable `seatId` that is never
reused. Visits and overrides refer to seats by this id, so they survive
relabelling, renumbering and inserting rows; labels ("G 10") are derived from
the layout when displayed. A seat that is deleted and redrawn gets a new id
once the layout is saved: within one editing session, a seat painted away and
back as a seat keeps its id and its fixed label.

### 4.4 Visit
| Field | Type | Notes |
|---|---|---|
| id | Long | |
| hallId | Long | FK → Hall |
| date | LocalDate | defaults to today |
| startTime | LocalTime? | v1.4: the showing's start, Berlin clock time; filled in by the ticket import, optional otherwise; needed for reminders (§11.3) |
| filmTitle | String? | |
| seatId | Int? | the seat sat in (§4.3 seat identity); picked on the seat map |
| screeningFormat | Enum? | OV, OmU (OV + German subs), German dub, 3D, 70mm, Other |
| hallRatings | 6 × Int? (1–5) | sound, picture, screen size, seat comfort, legroom, seating steepness |
| seatRating | Int? (1–5) | "How was this seat?" |
| note | String? | free text |
| photoPath | String? | photo stored in app-private storage (relative path) |

**All ratings are optional.**

A visit whose date and start time lie in the future is an **upcoming visit**
(v1.4). It is stored like any other visit (it simply has no ratings yet), so
counts include it from the moment it is saved, and scores are unchanged until
it is rated.

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

### 4.7 Ticket (v1.4)
At most one per visit; created by the ticket import (§11.2), deleted with its
visit.

| Field | Type | Notes |
|---|---|---|
| visitId | Long | FK → Visit, unique |
| bookingNumber | String | Yorck's "Buchungsnummer" (letters and digits); unique across tickets, so a ticket is recognised when imported again. It is also what the QR code contains. |
| seats | list of (row, seat) labels | every seat of the booking as printed (empty if they couldn't be read from the ticket); the visit's own seat is normally one of them |
| qrModules | bit grid? | the QR code's modules as read from the PDF (21 × 21 for Yorck's tickets); removed 7 days after the showing |

Never stored: the PDF itself, the "booked by" name, prices, transaction and
ticket numbers.

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
From v1.1 there are four: **Cinemas**, **Program** (§6.9), **Rankings**,
**Settings**.

### 6.1 Cinemas (home)
- List of all cinemas: name, overall score, number of visits.
- Sort: score (default; unrated last) / name / most visited. A fourth chip,
  **Favourites**, shows only the favourite cinemas (in score order); favourite
  cinemas carry a ★ marker in the list.
- FAB "Add visit" (quick entry: pick cinema → hall).
- **Next visit** (v1.4): while a visit's showing is ahead or started less
  than 30 minutes ago, or the visit is today without a start time (until
  midnight), a card above the list shows the soonest such visit: film, day and
  start time (after the start it says "Started 20:15"), cinema · hall, seat,
  and "Show ticket" (§6.11) when it has a QR code. Tapping the card opens the
  visit.

### 6.2 Cinema detail
- Header: name, address, overall score, cinema category scores, and a ☆/★
  toggle to mark the cinema as a favourite.
- List of halls with overall score and a ⭐ marker if a favourite seat is set.
- Actions: edit cinema, add hall.
- **Now showing** (v1.1): this cinema's showings of the next 7 days from the
  downloaded program, by day, with "Show all in Program" (opens the Program
  filtered to this cinema). It only reads the downloaded program and never
  downloads itself; without one it points to the Program tab (§11.1).

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
2. Date, start time (optional, v1.4), film title, screening format
3. Seat — picked by tapping the seat map, which shows the seat numbers (or skipped)
   - v1.4: a visit with a ticket shows a **Ticket** section here: booking
     number, all seats of the booking, "Show ticket" (§6.11) and "Remove
     ticket".
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
  counts and how many layouts are drawn); All visits (§6.8); Import ticket
  (v1.4: picks a ticket PDF with the file picker, §6.10)
- **Reminders** (v1.4, §11.3): "Before the showing" switch with the lead time
  (1, 2 or 3 hours; default 2) and "Rate after the visit" switch, both off
  until switched on. Switching one on asks for Android's notification
  permission; if it is refused, the switch stays off and a note explains how to
  allow notifications in Android's settings.
- **About:** version and build ID (the short commit hash the APK was built
  from; it matches the APK file name from CI), note that the app is unofficial
  and not affiliated with Yorck Kinos
- **Program** (v1.1): "Download program from yorck.de" switch (off until the
  owner agrees on first use; off = the app makes no network requests at all),
  date of the last download, "Delete downloaded program"

### 6.8 All visits
- One chronological list of every visit across all cinemas, newest first.
  v1.4: upcoming visits (§4.4) come first in their own **Upcoming** section,
  soonest first, with their start time.
- Each entry: film, date, cinema · hall, seat, screening format, seat rating.
- Tap to open/edit the visit (§6.4).
- Reached from Settings → Data → All visits.

### 6.9 Program (v1.1)
- **First use:** explains that the app will download Yorck's public program
  from www.yorck.de and asks for permission ("Download program" / "Not now").
  Nothing is downloaded before that.
- **Filters** (remembered between visits):
  - Cinemas: several can be picked; a "Favourites" shortcut picks the
    favourite cinemas.
  - Days: Today (default) / Tomorrow / This week (until Sunday) / Weekend /
    chosen dates.
  - Start time: from–to (e.g. 16:00–17:30); default any time.
  - Version: German (DF), OmU (German subtitles), OmeU (English subtitles),
    OV; events (e.g. Sneak, Premiere) as their own labels.
  - Film: search by title.
- **Saved filters:** a filter can be saved under a name (e.g. "After work:
  delphi LUX, Mon–Fri, 16–17:30, OmU/OV") and picked again from a list.
- **Results:** grouped by day, sorted by start time. Each row: time, film
  title, cinema (with the cinema's overall score and ★ if it is a favourite),
  version labels, runtime and "ends about" (start + runtime), and the hall for
  cinemas with one hall (§11.1). Past showings are hidden.
- **Showing actions:** "Book on yorck.de" (opens the showing's seat selection
  in the browser) and "Log visit" (opens Add visit pre-filled, §11.1).
- **Film page:** title, tagline, genre, runtime, age rating (FSK), release
  date and all upcoming showings by cinema and day; "Open on yorck.de".
- **State:** "as of" date and time of the downloaded program; pull to refresh.
  Clear messages when downloading is switched off, when offline, and when the
  page could not be read (the last good program stays visible).
- No posters or other images from Yorck.

### 6.10 Ticket import (v1.4)
- **Entry:** share the ticket PDF from the email app to Cinexperience (Share
  menu), open it with Cinexperience ("Open with"), or Settings → Data →
  Import ticket. The file name does not matter (Yorck names every ticket
  `ticket.pdf`).
- The app reads the ticket (§11.2) and opens **Add visit** (§6.4) pre-filled:
  cinema, hall, date, start time, film title, the seat, and the Ticket section.
  If the downloaded program (§11.1) has the showing (same cinema and start
  time), the screening format is filled in too. Anything that could not be
  matched (e.g. a hall or seat missing from the app's layout) stays empty for
  manual entry, with a note saying what was printed on the ticket.
- **Several seats:** the first seat of the booking is pre-selected as the
  owner's seat; the seat map shows which seats belong to the booking so another
  one can be picked.
- Nothing is stored until Save; Back discards the import.
- **Imported before** (same booking number): the app says so and opens that
  visit instead; if its QR code had been removed, it is stored again.
- **Not readable** (not a Yorck ticket, damaged, a format the app does not
  know): a clear message; nothing is stored.

### 6.11 Ticket (v1.4)
- Opened from the Next visit card (§6.1), the visit's Ticket section (§6.4) and
  the reminder (§11.3).
- Shows the QR code as large as fits, drawn sharply from its modules, **black
  on white** (see §7), with the brightness at maximum and the screen kept on
  while it is shown. Below: film, day and start time, cinema · hall, all seats,
  and the booking number in large type, as a fallback if the code can't be
  scanned.
- Works offline. Seven days after the showing the QR code is removed; the
  screen then shows the booking number with a note.

## 7. Look & feel

- **Dark theme only** in v1 (comfortable to use in a dark auditorium).
- Visual style loosely inspired by Yorck's branding: near-black background,
  bold uppercase display type, **black-and-white only** — the seat colours
  (green / yellow / red) are the only colours in the UI, so the seat map stands out
  (the one exception: the layout editor highlights the selected screen in green). No Yorck logos or trademarks are
  used in the app.
- **Ticket QR code** (v1.4): the only light surface in the app. The QR code is
  drawn black on a white panel with a quiet zone, because entrance scanners
  read dark-on-light codes reliably; the rest of the ticket screen stays dark.
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
- **Program (v1.1):** the downloaded program is not part of backups or Auto
  Backup (it can always be downloaded again). Saved program filters and the
  download switch are app settings, covered by Auto Backup, not by the backup
  file; the backup format does not change.
- **Tickets (v1.4):** the backup (`backupVersion` 3) adds each visit's start
  time and each ticket's booking number and seats. The QR code's modules are
  not in the backup; after a restore, importing the ticket PDF again brings
  the QR code back (the booking number identifies the visit). The reminder
  settings are app settings (Auto Backup, not the backup file).

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
is deleted. A cinema or hall the user deleted stays deleted: a later seed
version doesn't bring it back. Halls the seed adds to a cinema the user created
stay deletable. One exception: a layout that is still exactly the one an older
seed supplied (the user never edited it) is replaced by the newer seed's layout,
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
| Images | Decoded by the app's own file stores, no image library (§11.1 forbids image libraries); photos copied into app-private storage |
| Serialization | kotlinx.serialization (backup JSON; v1.1 also the program data) |
| Network (v1.1) | Android's built-in HTTPS client, only in `core:program`; no third-party network or HTML library (§11.1) |
| Ticket PDF (v1.4) | The app's own small reader for Yorck's ticket PDFs, in `core:ticket`; no PDF, OCR or QR-code library (§11.2) |
| Reminders (v1.4) | Android's AlarmManager and notifications; no new library (§11.3) |
| Seat map | Custom Compose `Canvas` with zoom/pan |
| Build | Gradle (Kotlin DSL), version catalog |
| Testing | Unit tests for rating logic & backup; Compose UI tests for key flows |
| Distribution | Signed APK, sideloaded (see §14) |
| CI | GitHub Actions: build, tests, screenshots (see §14) |

## 11. Future versions

### 11.1 v1.1: Program (Yorck showtimes)

The Yorck app and website lack good filters. v1.1 shows Yorck's program in
the app with the filters of §6.9, read-only; booking stays on yorck.de.

**Source.** Yorck's public page `https://www.yorck.de/en/films` contains the
whole program as data embedded in the page (no login, no API key; robots.txt
allows it). Checked on 2026-10-15: 14 cinemas, 114 films and 52 specials
(festivals and series), 977 showings, up to March 2027.
- Per showing: start date and time, cinema, version labels (DF, OmU, OmeU,
  OV, plus event labels such as Sneak or Premiere) and an ID (e.g.
  `1001-90006`). The start time is Berlin clock time; Yorck labels it
  `+01:00` all year, so the app accepts only `+01:00`, `+02:00` or no offset
  and ignores it. `KF` is shown as "Kids" (children's film); `SV` is shown as
  "SV" (most likely a special screening, not confirmed).
- Per film: title, tagline, genre, runtime, age rating (FSK), release date and
  the film's page name (slug).
- Specials (festivals and series) are listed like films, by kind and page
  name. A showing listed under both a film and a special belongs to the film
  and is linked to the special, so the special's page and a search for its
  title still find it.
- **Not included: the hall.** Yorck only shows it in its booking system (the
  seat selection loads it afterwards). The app never accesses the booking
  system, so the hall is shown only for the six cinemas with a single hall
  (Capitol Dahlem, Cinema Paris, Delphi Filmpalast, International, Neues Off,
  Odeon). For the others, the seat selection on yorck.de shows it, and the
  ticket import (§11.2) fills it into the visit.
- Yorck's cinema names are matched to the app's cinemas by a fixed table
  (e.g. "Kino International" → International); a cinema without a match is
  shown with Yorck's name and no score.

**Links.** The app builds every link itself from a fixed pattern; it never
opens an address taken from the page:
- Showing: `https://www.yorck.de/en/checkout/seats?sessionid=<ID>` opens the
  showing's seat selection (works without login; nothing is reserved until a
  seat is tapped there). Used only for upcoming showings whose ID has the form
  digits-dash-digits.
- Film: `https://www.yorck.de/en/films/<slug>`, also the fallback when a
  showing's ID is unusable.
- Special (festival or series): `https://www.yorck.de/en/specials/<slug>`,
  likewise the fallback for a showing owned by a special.
- Links open in the phone's browser, never inside the app: login, seat choice
  and payment happen only in the browser.

**Log visit from a showing.** Opens Add visit (§6.4) with cinema, date, film
title and screening format filled in (DF → German dub, OmU → OmU, OV → OV;
other labels leave the format empty). The hall is pre-selected for cinemas
with one hall; otherwise the owner picks it.

**Downloading and storage.**
- The program is downloaded only when the Program tab is opened and the last
  download is older than 6 hours (or none exists), or on pull to refresh;
  never in the background. One download is about 3 MB.
- It is stored as one cache file in app-private storage
  (`files/program/program.json`, the app's own format), not in the database.
  A new download replaces it only after it has been fully read and checked;
  if anything fails, the last good program stays.
- Without a connection, the last program is shown with its "as of" time.

**Network & security** (the app's first network access; these rules are
binding):
1. Consent: nothing is downloaded before the owner agrees on first use; the
   Settings switch turns downloading off again (off = no network requests).
2. One request only: `GET https://www.yorck.de/en/films`, with no
   parameters, no cookies, no account and nothing from the owner's data.
   Filters, favourites, visits and ratings never leave the phone.
3. One place: only the `core:program` module contains network code; a test
   fails the build if any other module uses network APIs. The address is
   fixed; any other host, and any redirect away from
   `https://www.yorck.de`, is refused.
4. No third-party network, HTML or image libraries: Android's built-in HTTPS
   client and kotlinx.serialization (already used for backups).
5. Encryption: HTTPS only; unencrypted traffic is blocked by the network
   security config; only the phone's system certificate authorities are
   trusted (no user-installed ones). No certificate pinning: Yorck's routine
   certificate changes would break the feature, and the data is public.
6. Limits: connect and read timeouts, at most 10 MB per download, and upper
   limits on films and showings far above today's numbers; anything larger is
   rejected.
7. Untrusted data: no WebView, no JavaScript, no HTML rendering. The app cuts
   the embedded data block out of the page by a plain text search, parses it
   as JSON into its own types and checks every field (lengths, allowed
   characters, valid dates and times; IDs and slugs against strict patterns);
   control and format characters are removed. Invalid entries are skipped; if
   nothing valid remains, the download is rejected. All texts are shown as
   plain text.
8. No images from Yorck (no posters).
9. Separate data: the program file is never written into the database or
   backups and is excluded from Auto Backup.
10. Tests: a small fixture with the page's structure (reduced, not a copy of
    Yorck's page) plus broken, oversized and manipulated variants; the app
    shows an error and never crashes. The network-module test from rule 3.
11. Review: a security review of the change before it is merged, and an
    independent review agent.

**Non-goals (v1.1):** booking inside the app; posters or other images; the
hall for cinemas with several halls; notifications (§11.3); any use of the
Yorck account or booking system.

**Checked before building (2026-10-15):**
- Times: a showing labelled `15:00+01:00` on 15 Oct (summer time) is shown by
  Yorck as 15:00, so the clock time is Berlin time.
- `KF` marks children's films. `SV` is still unconfirmed and shown as is.
- Terms of use: the ticket-store terms say nothing about automated access;
  the imprint provides the website "for your personal use" and protects its
  content. The app is personal use, and no Yorck content is committed to the
  repository (test fixtures are invented). The owner chose to go ahead.
- The showing link opens that showing's seat selection (checked without
  selecting a seat).

### 11.2 v1.4: Ticket import

Linking directly to a Yorck customer account is **not planned**: there is no
public API, and it would require storing the Yorck password and relying on
undocumented endpoints that can break at any time.

Instead, v1.4 imports the **ticket PDF** that Yorck attaches to the booking
email (screens: §6.10, §6.11). The email itself is not read; the app never
accesses the mailbox, the Yorck account or the network for this. It also fills
in the hall that the program (§11.1) cannot provide.

**The ticket** (checked on a real ticket, October 2026): one A3 page created by
Yorck's ticket system (PDF 1.4, producer "Qt 5.5.1"); German labels; the text
is embedded (Arial with a Unicode table), and the QR code is a small embedded
JPEG image.
- Read: **Buchungsnummer** (booking number), film title (the bold line or lines
  between "Ticket" and the first label; "Gebucht von" is bold too), **Kino** (cinema), **Datum** (dd.MM.yyyy), **Zeit** (HH:mm, Berlin
  clock time), **Saal** (hall), **Reihe** (row), **Platz** (seat).
- Ignored and never stored: "Gebucht von" (the booker's name), prices,
  categories, transaction and ticket numbers, the address block and the
  conditions text.
- **QR code:** a version-1 QR code (21 × 21 modules) whose content is the
  booking number. The app does not decode or generate QR codes: it reads the
  module grid from the embedded image (threshold, then the centre of each
  module) and checks it (the three finder patterns, the timing patterns and the
  format information). Only a grid that passes is kept, and the ticket screen
  redraws it sharply from these modules. Versions 1–4 (up to 33 × 33 modules)
  are accepted, in case longer booking numbers appear.
- **Still open:** a booking with several seats (one page per ticket? two
  numbers after "Platz"?). Until a sample exists, the reader accepts several seats where the
  layout is unambiguous and otherwise leaves the seat for manual entry.

**Matching:** the cinema through the Yorck-name table of §11.1, with the name
printed after "Kino" looked up as printed and with "Kino " in front (the table
knows e.g. "Kino International"); the hall by name within that cinema ("Saal"
and its number, other hall names as printed); row and seat
through the hall layout's labels (§4.3, which match Yorck's booking); the
showing in the downloaded program by cinema and start time, for the screening
format (§11.1 mapping) and the runtime (§11.3).

**Security & privacy** (binding, like §11.1):
1. The PDF is untrusted. The app's own reader handles only the structure
   above (classic cross-reference table, compressed content streams, Type 0
   fonts with a Unicode table, JPEG images) and refuses anything else: files
   over 2 MB, more than 4 pages, encrypted files, compressed streams over 2 MB
   unpacked, more than 2,000 objects; further limits cap the unpacked total,
   nesting depth, glyphs and images per page. Only images of at most 512 × 512 pixels
   are decoded, as QR code candidates (Yorck's is 116 × 116; the large logo is
   skipped). It never runs scripts, opens links or loads anything referenced
   by the file.
2. Every field is checked like the program data (§11.1 rule 7): lengths,
   allowed characters, a valid date and time; control and format characters
   are removed; all texts are shown as plain text.
3. Only the fields listed above are kept; the file itself is never stored.
4. No PDF, OCR or QR-code library, no network code, no new permission for the
   import.
5. Tests: invented fixture PDFs with the same structure (not copies of a real
   ticket), plus damaged, oversized and manipulated variants; the app shows a
   message and never crashes. A real ticket is only checked locally (path in an
   environment variable, file in a git-ignored folder), like the program's
   real-page test. **No real ticket, booking number, name or QR code is ever
   committed.**
6. Review: a security review and an independent review agent before merging.

### 11.3 Notifications

Possible notifications, all opt-in. **v1.4 implements e and f** for upcoming
visits (§4.4) that have a start time, whether from a ticket or entered by hand;
a–d stay ideas.

| # | Notification | Example | Background download? |
|---|---|---|---|
| a | Watchlist | A marked film (also an upcoming one from Yorck's "coming soon" list) gets showings: "Hope is now showing in OmU at delphi LUX, first showing Fri 17:15" | yes, once a day |
| b | Saved filter | A saved filter (§6.9) gets new showings | yes, once a day |
| c | Weekly digest | Monday morning: "This week at your favourite cinemas: 12 showings match your filters" | yes, once a week |
| d | Last chance | A watchlist film has its last showings this week | yes, once a day |
| e | Showtime reminder (v1.4) | "Hope starts at 17:15 · delphi LUX, Saal 1 · Row 7 · Seat 10", with "Show ticket" when the visit has a QR code; 1, 2 or 3 hours before (Settings, default 2) | no |
| f | Rate your visit (v1.4) | "How was Hope? Rate your visit" about 30 minutes after the film has ended; opens the visit's form | no |

- a–d would download the program in the background, which §11.1 excludes
  (downloads only when the Program tab is opened). They would need their own
  switch (off by default, optionally Wi-Fi only) and the same single fixed
  request.

**Rules for e and f (v1.4):**
1. Both are off until switched on in Settings (§6.7); switching one on asks
   for Android's notification permission once.
2. Times are computed from the visit's date and start time in Berlin time. The
   end of the film is start + runtime when the downloaded program has the
   showing (§11.2 matching), otherwise start + 2.5 hours.
3. A reminder whose time has already passed when it is scheduled is not shown
   (so nothing arrives late, e.g. after a restart); "Rate your visit" is
   skipped if the visit already has a rating by then.
4. Reminders are scheduled again whenever a visit is saved, changed or
   deleted, the settings change, the phone restarts, the app is updated, the
   time zone changes or a backup is imported.
5. They use Android's exact alarms, so a reminder arrives on time, also when the
   app hasn't been opened for days; the phone is woken only at the reminders'
   times (and again, with growing pauses, if planning fails).
6. New permissions: notifications (`POST_NOTIFICATIONS`, asked at runtime),
   receiving the restart broadcast (`RECEIVE_BOOT_COMPLETED`) and exact alarms
   (`USE_EXACT_ALARM`, granted at install, no prompt). Nothing is downloaded;
   the reminders work entirely on the phone.
7. Tapping "Show ticket" opens the ticket (§6.11); tapping the notification
   itself opens the visit (§6.4).

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
| M9 | Program | Yorck's program with filters, film page, "Now showing", links to yorck.de, log visit from a showing (v1.1, §11.1) |
| M10 | Ticket import & QR code | Ticket PDF reader, import into Add visit, ticket screen with the QR code, Next visit card, Upcoming in All visits; schema and backup version 3 (v1.4, §11.2, §6.10, §6.11) |
| M11 | Reminders | Showtime reminder and "Rate your visit" (v1.4, §11.3) |

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
| `feature:backup` | The Settings tab (§6.7): backup export / import with preview, restore safety copy, Settings → Program, About, entries to Manage cinemas & halls and All visits | Medium |
| `core:program` | v1.1: downloading, checking and caching Yorck's program; the only module with network code (§11.1) | High |
| `feature:program` | v1.1: Program tab, filters, saved filters, film page (§6.9) | Medium |
| `core:ticket` | v1.4: the ticket PDF reader, the ticket fields and checks, the QR module grid (§11.2) | High |
| `feature:tickets` | v1.4: ticket import (Share menu, Open with, and the file from Settings → Import ticket, whose row in `feature:backup` opens the file picker), the ticket screen with the QR code (§6.10, §6.11) | Medium |
| Reminders | v1.4: scheduling rules (`core:model`), alarms, notifications and the restart receiver (`app`) (§11.3) | Medium |

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
| `core:program`: download, parser, cache (v1.1) | xhigh | First network access; parses untrusted data (§11.1 security rules) |
| `feature:program`: Program tab & film page (v1.1) | medium | Standard screens on top of `core:program` |
| `core:ticket`: ticket PDF reader (v1.4) | xhigh | Parses untrusted files (§11.2 security rules) |
| Schema & backup version 3 (v1.4) | high | Existing data must survive the migration |
| `feature:tickets`, reminders (v1.4) | medium (high for scheduling) | Standard screens; reminders must not fire wrongly |
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
- A ticket PDF for a booking with several seats (§11.2); a one-seat ticket
  was checked in October 2026.
- v1.1 Program: the meaning of the `SV` label (§11.1).
- Yorck-app screenshots of hall seat plans (for preloaded layouts).
