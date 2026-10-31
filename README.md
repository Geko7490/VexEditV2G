# VEX EDIT STUDIO

Native Android video editor built with Kotlin and Jetpack Compose.

## Project setup

- Application ID: `com.vexedit.studio`
- Android namespace: `com.example` (kept temporarily to preserve existing Kotlin and generated-resource references during the package migration)
- Minimum SDK: 26
- Compile SDK: 35
- Target SDK: 35
- Gradle wrapper: 9.3.1
- JDK: 17

## Build a debug APK

### Linux / macOS

```bash
./gradlew :app:assembleDebug
```

### Windows

```bat
gradlew.bat :app:assembleDebug
```

The APK is generated at:

```
app/build/outputs/apk/debug/app-debug.apk
```

Run unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

## GitHub Actions

The Android Debug Build workflow runs on pushes to `main` and `stabilize/android-build`, pull requests targeting `main`, and manual dispatch. It runs unit tests, builds the debug APK, and uploads the APK as a workflow artifact.

## Development status

This repository is an active development baseline, not a claim that every feature in the product master specification is complete. In particular, the existing editor, playback, export, and module implementations require staged functional verification and improvement. Preserve the current VEX visual layout while developing real editing behavior; do not replace the native app with a web implementation.

## UI design constraints

- Dark VEX palette with purple accent `#A855F7`.
- Prefer sharp, compact surfaces.
- Standard component corner radius: 6–8dp maximum unless a circular control is functionally appropriate.
- Avoid unnecessary rounded cards, oversized spacing, and decorative-only controls.
