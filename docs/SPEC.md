# Cinexperience — Product Specification

**Status:** Draft v1.0 (agreed in planning, no development started)
**Owner:** katznmeik
**Last updated:** 2026-10-13

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
- **Supported Android versions:** minSdk 34 (Android 14), targetSdk 36 (Android 16).
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

### 4.2 Hall
| Field | Type | Notes |
|---|---|---|
| id | Long | |
| cinemaId | Long | FK → Cinema |
| name | String | e.g. "Saal 1" |
| layout | SeatLayout? | null until drawn by the user |
| notes | String? | |

### 4.3 SeatLayout (grid)
A rectangular grid of `rows × columns` cells. Each cell is one of:
- `SEAT` — a bookable seat, with a row label and seat number
- `GAP` — empty space (e.g. short rows, irregular shapes)
- `AISLE` — walkway (visually distinct from a gap)

Row labels and seat numbers are auto-generated (row 1…n, seat 1…m per row,
skipping gaps/aisles) and can be edited to match the cinema's real labels.
The screen is always drawn at the top.

### 4.4 Visit
| Field | Type | Notes |
|---|---|---|
| id | Long | |
| hallId | Long | FK → Hall |
| date | LocalDate | defaults to today |
| filmTitle | String? | |
| seatRef | (row, seat)? | the seat sat in; picked on the seat map |
| screeningFormat | Enum? | OV, OmU (OV + German subs), German dub, 3D, 70mm, Other |
| hallRatings | 6 × Int? (1–5) | sound, picture, screen size, seat comfort, legroom, seating steepness |
| seatRating | Int? (1–5) | "How was this seat?" |
| note | String? | free text |
| photoUri | String? | photo stored in app-private storage |

**All ratings are optional.**

### 4.5 CinemaRating
Cinema-level categories, rated separately from halls (1–5, optional):
- Atmosphere
- Bar / snacks
- Popcorn quality

Stored per visit (the visit's hall → cinema) so they average over time like hall
ratings. Shown as an optional section in the "Add visit" form.

### 4.6 SeatOverride
| Field | Type | Notes |
|---|---|---|
| hallId, row, seat | | identifies the seat |
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
- Sort: name (default) / score / most visited.
- FAB "Add visit" (quick entry: pick cinema → hall).

### 6.2 Cinema detail
- Header: name, address, overall score, cinema category scores.
- List of halls with overall score and a ⭐ marker if a favourite seat is set.
- Actions: edit cinema, add hall.

### 6.3 Hall detail
- **Seat map** (zoomable/pannable) coloured per §5. Tap a seat →
  bottom sheet: average seat rating, visits in that seat, set colour override,
  set/unset favourite.
- **Score breakdown:** six categories with bars and rating counts.
- **Visit history:** newest first; tap to open/edit a visit.
- Button **"Add visit"** (hall pre-selected).
- If no layout yet: prompt "Draw seat layout" → layout editor.

### 6.4 Add / edit visit
Single scrolling form:
1. Cinema & hall (pre-filled when opened from a hall)
2. Date, film title, screening format
3. Seat — picked by tapping the seat map (or skipped)
4. Seat rating
5. Hall ratings (six star rows)
6. Cinema ratings (collapsible)
7. Note, photo (camera or gallery)

Save is always allowed — nothing besides hall is required.

### 6.5 Layout editor
- Set number of rows and columns.
- Tap/drag cells to cycle SEAT → GAP → AISLE.
- Edit row labels and starting seat numbers.
- Preview with screen at the top; save / discard.
- Changing a layout that already has visits warns if seats with data would
  disappear.

### 6.6 Rankings
- Choose scope: **Halls** or **Cinemas**.
- Choose category (overall or any single category).
- Sorted list, highest first; items with no data at the bottom.

### 6.7 Settings
- Export backup / Import backup (§8)
- Manage cinemas & halls (add, rename, delete user-added ones)
- About / version

## 7. Look & feel

- **Dark theme only** in v1 (comfortable to use in a dark auditorium).
- Visual style loosely inspired by Yorck's branding: dark background,
  clean typography, restrained accent colour. No Yorck logos or trademarks are
  used in the app.
- Seat colours must be distinguishable in the dark theme and also carry a
  secondary cue (icon/pattern) for colour-blind readability.
- Material 3 components via Jetpack Compose.

## 8. Backup & restore

- **Export:** writes one `.zip` file containing `data.json` (all cinemas,
  halls, layouts, visits, overrides) and the `photos/` folder. Saved through
  the Android file picker (e.g. Google Drive, Downloads).
- **Import:** pick a backup file → preview counts → replace all current data
  (with confirmation).
- The JSON includes a `schemaVersion` so older backups can be migrated.
- **Android Auto Backup** is enabled for the database as an additional safety net.

## 9. Preloaded data

The app ships with a seed list of Yorck cinemas and their halls
(names + addresses; no seat layouts). The list below is **preliminary and must
be verified against yorck.de in milestone M1** — the chain changes venues over time.

Babylon (Kreuzberg), Capitol Dahlem, Cinema Paris, Delphi Filmpalast,
Delphi LUX, Filmtheater am Friedrichshain, International, Kant Kino,
Neues Off, Passage, Rollberg, Yorck.

Users can add, rename or delete cinemas and halls at any time.

## 10. Technical approach

| Area | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3 |
| Architecture | Single-activity, MVVM, unidirectional data flow |
| Persistence | Room + Kotlin Flow |
| DI | Hilt (or manual DI if kept small) |
| Navigation | Navigation Compose |
| Images | Coil; photos copied into app-private storage |
| Serialization | kotlinx.serialization (backup JSON) |
| Seat map | Custom Compose `Canvas` with zoom/pan |
| Build | Gradle (Kotlin DSL), version catalog |
| Testing | Unit tests for rating logic & backup; Compose UI tests for key flows |
| Distribution | Signed release APK, sideloaded |

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
| M0 | Project setup | Android project, theme, navigation skeleton, CI build |
| M1 | Data foundation | Room schema, verified seed data, cinema & hall CRUD |
| M2 | Layout editor | Grid editor, seat map rendering |
| M3 | Visit logging | Add/edit visit form incl. seat picker, photo, format |
| M4 | Scores & seat map | Rating aggregation, seat colours, overrides, favourite |
| M5 | Rankings | Sortable rankings for halls and cinemas |
| M6 | Backup | Export / import zip, Auto Backup |
| M7 | Polish & release | Accessibility pass, app icon, signed APK → v1.0 |
| M8 | Ticket import | Share-intent parser (v1.1) |

## 13. Open items

- Verify the current list of Yorck cinemas and hall names (M1).
- Sample ticket email/PDF for v1.1 parser.
- App icon and final accent colour.
