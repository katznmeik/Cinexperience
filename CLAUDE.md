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
./gradlew recordRoborazziDebug    # PNGs -> app/build/outputs/roborazzi/
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
| `core:data` | Android lib + Room/KSP | database, DAOs, repositories, seed data |
| `core:ui` | Android lib + Compose | `CinexTheme`, colour/type tokens, fonts, icons, shared components |
| `feature:cinemas` | feature | cinema list (start tab), cinema detail |
| `feature:seatmap` | feature | hall detail with seat map, layout editor |
| `feature:visits` | feature | add/edit visit, all visits |
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
- **DI:** manual (`app/.../di/AppContainer.kt`), no Hilt.
- **Data:** no invented sample data in the app.
- **Seats** are identified by a stable `SeatId` stored in the layout (see
  `core/model/.../SeatLayout.kt`), never by grid position or label. Edit layouts
  with the `SeatLayout` editing functions so ids stay attached to their cells;
  derive labels with `layout.labels()`.
- **Scores and seat colours** come from the `core:model` functions
  (`hallScores`, `cinemaScores`, `seatStates`), never from SQL math.
- **Schema changes:** bump the `CinexDatabase` version, add a migration to
  `ALL_MIGRATIONS`, commit the exported `core/data/schemas/.../<n>.json` and add
  a case to `MigrationTest`. No destructive fallback.
- **Seed data:** `core/data/src/main/assets/seed/yorck.json`. Bump `seedVersion`
  for every change; `seedKey`s are permanent. Seeding only inserts missing items
  and fills empty fields, it never overwrites or deletes.
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
