# Cinexperience

A personal Android app for rating the halls and seats of the Berlin cinema chain
Yorck Kinos. You log every visit, and over time the app shows which cinema, which
hall and which seat to book next. It works offline, has a dark theme only
and is installed directly from an APK. It is unofficial and not affiliated with
Yorck Kinos.

The one exception to offline (from v1.1): the Program tab downloads Yorck's
public program page from www.yorck.de, only after you agree and never in the
background. Nothing from your data is sent; Settings can switch it off.

The full product specification is in [`docs/SPEC.md`](docs/SPEC.md).

## Features (v1.0)

- **Cinemas:** all Yorck cinemas and their halls are preloaded. Sort by score,
  name or most visited; add, rename or delete your own cinemas and halls.
- **Seat maps:** draw each hall's layout in a grid editor, tracing a screenshot
  of the Yorck app's seat plan (split and staggered rows, wheelchair, companion
  and sofa seats, screen size and distance). All 38 halls come drawn from
  Yorck's seat plans, with Yorck's row labels and seat numbers.
- **Visits:** date, film, screening format, your seat (tap it on the map or
  choose row and number), seat rating, six hall ratings, cinema ratings, a note
  and a photo.
- **Hall detail:** the seat map coloured by your ratings (great, OK, avoid,
  no data; avoid seats carry a ×), a favourite seat with a star, the viewing
  angle of each seat, scores per category and the visit history.
- **Rankings:** halls or cinemas, overall or by any category.
- **Backup:** export everything (including photos) to one `.zip` file and import
  it again, with a safety copy of the data it replaces.
- Works with TalkBack and large font sizes.

## Features (v1.1)

Four tabs: **Cinemas · Program · Rankings · Settings**.

- **Program:** Yorck's showtimes (after you agree on first use), with filters by
  cinema (with a Favourites shortcut), day (today, tomorrow, this week, weekend
  or chosen dates), start time, version (DF, OmU, OmeU, OV and events) and film
  title; filters are remembered and can be saved under a name. Each showing
  shows the cinema's score, ★ for favourites, the runtime and when it ends about,
  and the hall of one-hall cinemas. "Book on yorck.de" opens the seat selection
  in the browser; "Log visit" opens Add visit pre-filled. A film page lists all
  its showings.
- **Now showing** on each cinema's page: its showings of the next 7 days from the
  downloaded program.
- **Settings → Program:** switch downloading off (no network requests at all),
  see the last download, delete the downloaded program.

## Install and update

Every version is published as a GitHub Release with the APK
`cinexperience-<version>.apk` attached (Releases, on the right of the repository
page). All releases are signed with the same key.

**Before updating** (once you have real data): open Settings → **Export backup**
and save the file somewhere outside the phone (e.g. Google Drive). Updating keeps
your data, the backup is only a precaution.

On the Galaxy S25 (One UI 8.5, Android 16):

1. Temporarily turn off **Auto Blocker**: Settings → Security and privacy →
   Auto Blocker. Otherwise apps from outside the Galaxy Store and Play Store are
   blocked.
2. Open the latest release in the phone's browser and tap
   `cinexperience-<version>.apk` to download it.
3. Open the downloaded file (from the notification or My Files → Downloads).
   If asked, allow **Install unknown apps** for the browser or My Files.
4. If **Play Protect** warns about an unknown app, tap More details →
   **Install anyway**. (It may offer to scan the app; that is fine.)
5. Tap **Install**, or **Update** if the app is already installed.
6. Turn Auto Blocker back on.

**Updating keeps all data** (visits, ratings, layouts, photos) because every
release is signed with the same key, so Android installs it as an update over the
existing app. Never uninstall the app to "update": uninstalling deletes its data.
If Android ever says the app can't be installed because of a conflict with the
existing package, the APK was signed with a different key (e.g. a debug build
from a CI run): don't uninstall, use the APK from the GitHub Release instead.

The app needs Android 14 or newer.

## Restore a backup

1. Settings → **Import backup** and pick the backup `.zip` file
   (`cinexperience-backup-<date>.zip`).
2. The whole file is checked first. If it is fine, the app shows when it was
   exported and what it contains; nothing has changed yet.
3. Tap **Replace**: all current data is replaced by the backup in one step. If
   anything goes wrong, the current data stays as it was.

Right before an import, the app saves the current data as a **safety copy** (the
latest two are kept). To undo an import, use Settings → **Restore safety copy**.

On a new phone: install the app as above, then import the backup. Android's own
Auto Backup also restores the database (not the photos) when you set up a new
phone with the same Google account.

## Development

### Build

Requirements: JDK 21 and the Android SDK (compileSdk 37, build-tools 36.1.0).
Android Studio installs the SDK for you. On Linux or cloud machines without it, run:

```sh
scripts/setup-android-sdk.sh        # SDK into $HOME/android-sdk + local.properties
```

The script also installs a Gradle init script,
`~/.gradle/init.d/maven-central-mirror.gradle`. It routes Maven Central (and
Robolectric's runtime downloads) through Google's Maven Central mirror, because
Maven Central often rate-limits cloud machines. It applies to **every Gradle
project on that machine**. To skip it, run the script with
`CINEX_NO_MAVEN_MIRROR=1`. To remove it, delete that file.

Downloads are checked before they are used: the script checks the command-line
tools zip against the SHA-1 Google publishes for it (`CMDLINE_TOOLS_SHA1`), and
the Gradle wrapper checks its distribution against `distributionSha256Sum` in
`gradle/wrapper/gradle-wrapper.properties`. Both stop on a mismatch.

Then:

```sh
./gradlew testDebugUnitTest         # unit, UI and accessibility tests
./gradlew recordRoborazziDebug      # screenshots -> <module>/build/outputs/roborazzi/*.png
./gradlew lintDebug                 # Android Lint: CI fails on errors (see below)
./gradlew assembleDebug             # debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease           # release APK (see Signing)
```

Lint reports no errors. It reports 16 warnings that are kept on purpose: the
portrait lock (2) and targetSdk 36 (1), both set by the spec; 2 dependency-update
hints; the framework `ExifInterface` (2, fine on minSdk 34); and 9 "use the KTX
extension" style hints. There is no lint baseline. Suppressed checks:

- `UsableSpace`, on `freeBytes` in `BackupReader.kt`: the import's free-space checks
  use the file system's free space on purpose, because `StorageManager`'s
  "allocatable bytes" count cached files as free, and staged backup files are cache
  files themselves.
- `MissingPermission`, on the reminders' exact-alarm call (`AlarmReminderScheduler`):
  lint knows only `SCHEDULE_EXACT_ALARM`; the app holds `USE_EXACT_ALARM`, which
  Android accepts as well.
- `UseKtx`, where the reminder intents' token (`ReminderIntentToken`) and the reminder
  settings (`ReminderSettingsStore`) are stored: they need `commit()`'s result, which
  the KTX `edit { }` doesn't return.

R8 / minification is off in v1 (the APK is small and only sideloaded); turning it
on later needs keep rules for kotlinx.serialization and Room checked on a device.

The seed generator (see Seed layouts) has its own Python tests (standard library
only, run first in CI):

```sh
python3 -I -B -m unittest discover -s scripts/seed -p 'test_*.py'
```

### Seed layouts

The preloaded layouts in `core/data/src/main/assets/seed/yorck.json` are
generated from yorck.de seat plans saved per hall (`<cinema>/hall-<name>.seatplan.json`
and `.dom.json`; this crawl is not part of the repository):

```sh
python3 -I scripts/seed/yorck_seatplan_to_layout.py <crawl dir> \
    core/data/src/main/assets/seed/yorck.json \
    core/data/src/test/resources/seed/yorck-seat-numbers.json
```

It rewrites the halls' layouts in the seed and the fixture of Yorck's seat
numbers that `YorckSeedTest` checks every seat against. Raise `seedVersion` by
hand afterwards, then compare `feature/seatmap/build/outputs/roborazzi/yorck_seed_*.png`
(`YorckSeedScreenshotTest`) with the seat plans on yorck.de.

### CI builds

Every push to a `claude/**` branch and every pull request runs GitHub Actions
(`.github/workflows/android.yml`): tests, screenshots, lint and an APK (when the
signing secrets exist, a release-signed APK whose certificate is checked like in a
release). The run's
artifacts contain the APK (`cinexperience-<sha>.apk`), the screenshots and the
test reports. These APKs are for testing on the PC emulator or the phone (spec
§14.2, §14.3); releases come from the release workflow below.

Settings → About shows the build ID next to the version, e.g. `Version 1.0.0 ·
Build 413a117`. It is the short commit hash the APK was built from, the same as
`<sha>` in the APK file name, so name it in bug reports.

### Versions and releases

The version lives in one place, `cinexVersionName` in `gradle.properties`
(`MAJOR.MINOR.PATCH`). The `versionCode` is derived from it:
`MAJOR * 10000 + MINOR * 100 + PATCH` (1.0.0 → 10000, 1.2.3 → 10203), so each new
version installs as an update.

To publish a version: raise `cinexVersionName` and merge it into the integration
branch. Then either run the release from GitHub (Actions → Release → Run
workflow, on the integration branch, enter the version, e.g. `1.0.0`; this works
from the phone, and it is how Claude releases when you ask it to), or push a tag
`v<version>` on that commit:

```sh
git tag v1.0.0
git push origin v1.0.0
```

A run from GitHub releases the newest commit of the branch and creates the tag
`v<version>` on it; it refuses a version whose tag already exists.
`.github/workflows/release.yml` checks that the tag matches the version,
runs the tests and lint, builds the release APK signed with the fixed key (it
fails if a signing secret is missing), checks with `scripts/verify-release-apk.sh`
that the APK verifies and its only signer is the release certificate, and creates
the GitHub Release with `cinexperience-<version>.apk` and short notes.

### Signing

All release builds are signed with one fixed key (spec §14.4), so each new APK
installs as an update and keeps your data. The key is never committed. CI reads
it from these repository secrets:

| Secret | Content |
|---|---|
| `CINEX_KEYSTORE_BASE64` | the `.jks` file, base64-encoded on one line |
| `CINEX_KEYSTORE_PASSWORD` | keystore password |
| `CINEX_KEY_ALIAS` | key alias (`cinexperience`) |
| `CINEX_KEY_PASSWORD` | key password |

To build a signed release locally, set `CINEX_KEYSTORE_PATH` (path to the
`.jks`), `CINEX_KEYSTORE_PASSWORD`, `CINEX_KEY_ALIAS` and `CINEX_KEY_PASSWORD`.
If they are missing, `assembleRelease` falls back to the debug key and prints a
warning. Keep a copy of the keystore and its passwords outside GitHub: without
them no future version can update the installed app.

The SHA-256 of the release key's certificate is committed in
`signing/release-cert.sha256` (public information, not a secret). CI and the
release workflow refuse an APK signed with any other certificate, because it
would not install over the existing app. Check a local build with
`scripts/verify-release-apk.sh app/build/outputs/apk/release/app-release.apk`
(set `CINEX_RELEASE_CERT_SHA256` to test a build signed with another key). The
key should never change; if it ever has to, every installed copy must be
uninstalled (losing its data unless restored from a backup) and the new
certificate's SHA-256 (`apksigner verify --print-certs`, "certificate SHA-256
digest") goes into that file.

## Licences

The bundled fonts, Archivo and Instrument Sans, are under the SIL Open Font
License 1.1. See `core/ui/fonts/`.
