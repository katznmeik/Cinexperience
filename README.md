# Cinexperience

A personal Android app for rating the halls and seats of the Berlin cinema chain
Yorck Kinos. You log every visit, and over time the app shows which cinema, which
hall and which seat to book next. It works fully offline, has a dark theme only
and is installed directly from an APK. It is unofficial and not affiliated with
Yorck Kinos.

The full product specification is in [`docs/SPEC.md`](docs/SPEC.md).

## Status

M0 (project setup) is done: modules, theme, navigation skeleton with placeholder
screens, CI, signing. Features arrive in later milestones (spec §12).

## Build

Requirements: JDK 21 and the Android SDK (compileSdk 37, build-tools 36.1.0).
Android Studio installs the SDK for you. On Linux or cloud machines without it, run:

```sh
scripts/setup-android-sdk.sh        # SDK into $HOME/android-sdk + local.properties
```

Then:

```sh
./gradlew testDebugUnitTest         # unit tests
./gradlew recordRoborazziDebug      # screenshots -> app/build/outputs/roborazzi/*.png
./gradlew assembleDebug             # debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease           # release APK (see Signing)
```

## Install the APK

Every push to a `claude/**` branch and every pull request runs GitHub Actions. The
run's artifacts contain the APK (`cinexperience-<sha>.apk`), the screenshots and
the test reports. To install it on the PC emulator or on the Galaxy S25, follow
spec §14.2 and §14.3.

## Signing

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
warning.

## Licences

The bundled fonts, Archivo and Instrument Sans, are under the SIL Open Font
License 1.1. See `core/ui/fonts/`.
