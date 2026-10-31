# VEX EDIT STUDIO

Native Android video editor built with Kotlin and Jetpack Compose.

## Project setup

- Application ID: `com.vexedit.studio`
- Android namespace: `com.example` (kept temporarily to preserve existing Kotlin and generated-resource references during the package migration)
- Minimum SDK: 26
- Compile SDK: 36
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

This repository is an active native Android editor. The stabilization branch is being developed in stages and is not a claim that every feature in the product specification is complete.

### Implemented in the stabilization branch

- Media3 ExoPlayer-based video preview with asynchronous preparation, seeking, playback state and decoder error reporting.
- Timeline operations that preserve absolute positions and gaps when splitting, trimming and reordering clips.
- Explicit gap-preserving delete (`deleteClip(id, ripple = false)`) and ripple delete (`deleteClip(id, ripple = true)`) behavior.
- Room-backed project persistence with asynchronous I/O and migration from the previous JSON project store.
- Simultaneous preview playback of active music, voice and SFX clips, including track mute/solo/fader and clip speed.
- Export composition of overlapping video/image layers and multiple text overlays.
- Stacked color-matrix export grading for brightness, contrast, warmth, cool-cyan, vintage and violet-glow effects.

### Known limitations still being worked on

- Export audio is not yet a true multi-source PCM mix; the current muxing path selects one audio source. Multi-track preview does not imply a finished multi-track export mix.
- The live preview currently displays one active video clip; layered video composition is implemented in the export renderer, not yet in the ExoPlayer preview surface.
- Several effect types (for example grain, RGB split, motion blur and advanced masks) still need real preview/export processors.
- Export still needs broader device/codec validation, cancellation/recovery tests and performance profiling on physical devices.

Keep the existing VEX native UI and working functionality. Do not replace the Android application with a web implementation.

## UI design constraints

- Dark VEX palette with purple accent `#A855F7`.
- Prefer sharp, compact surfaces.
- Standard component corner radius: 6–8dp maximum unless a circular control is functionally appropriate.
- Avoid unnecessary rounded cards, oversized spacing, and decorative-only controls.
