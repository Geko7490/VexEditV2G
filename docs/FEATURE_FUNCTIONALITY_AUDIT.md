# VEX EDIT STUDIO — Button Functionality Audit & Implementation Plan

Branch: `stabilize/android-build`
Scope: Existing UI actions only. Preserve the current native Kotlin/Jetpack Compose app. Do not replace screens with HTML/WebView.
Rule: A control is only marked complete when its effect is persisted and observable in preview and/or final export as appropriate.

## Verified working or substantially connected

- Project creation/open/rename/duplicate/favorite/delete: repository operations exist; verify end-to-end after UI smoke tests.
- Project persistence: Room is the primary store with legacy JSON migration and autosave snapshot fallback.
- Timeline split/move/trim/duplicate/delete: ViewModel operations exist; unit and interaction coverage still needs expansion.
- Keyframe add/previous/next: backed by ViewModel keyframe data. Save Transform writes base transform.
- Effects Apply: persists ClipEffect on the selected clip. Presets save/load/delete through SharedPreferences.
- Settings language and performance mode: persisted through preferences.
- Clear Cache: deletes app cache directories; blocked during export.
- Restore Last Project: reads autosave snapshot and opens the recovered project.
- Audio mixer fader/mute/solo and clip volume/fades: connected to timeline preview state. Export parity is not complete.

## Confirmed incomplete / non-functional controls

### A. Audio DSP tools — highest priority
The Audio Studio dialog explicitly says these processors are not connected:
- EQ
- Noise Gate
- Compressor
- Reverb
- Delay
- Limiter

Implementation plan:
1. Add typed, validated per-clip DSP settings to the project model and versioned JSON/Room serialization.
2. Build a shared audio-processing layer with deterministic parameter ranges and unit tests.
3. Connect preview playback to the processor chain; avoid merely saving settings without audible processing.
4. Build an export audio pipeline that decodes source audio to PCM, applies clip/track gain, fades, mute/solo, EQ and DSP, mixes all active clips on the timeline, then encodes AAC and muxes it with video.
5. Keep unsupported effects disabled until preview and export both apply them correctly.

### B. Multi-clip audio export — highest priority
Current ExportEngine audio muxing selects one audio source and copies compressed samples. It does not mix all timeline audio clips and cannot apply gain/fades/DSP to compressed samples.

Implementation plan:
1. Decode all audio clips to PCM.
2. Respect clip start, source in-point, duration, speed, track gain, clip gain, mute/solo and fade envelopes.
3. Mix overlapping clips with clipping protection/limiting.
4. Encode one AAC mix track and mux it into the rendered video.
5. Add tests for overlapping clips, delayed starts, trims, fades and mute/solo.

### C. Effects Lab reset — fixed in this audit pass
Reset previously changed only local slider values and left the applied effect on the clip. It now resets the controls and removes the selected effect from the target clip.

### D. Effects preview/export parity
The preview and export paths must use the same effect parameter interpretation. Audit every item in supportedEffectTypes against both pipelines; do not expose an effect as supported unless both paths render it. Add image-based renderer tests for intensity, opacity, radius and blend.

### E. Audio Studio preview and transport
- The mini preview currently displays a thumbnail rather than a live frame-accurate video preview.
- Verify the transport controls seek to project boundaries and that audio/video stay synchronized.
- Replace the static ruler labels with time values derived from project duration and playhead.
- Keep waveform visuals derived from actual decoded samples; do not use placeholder waveforms for imported media.

### F. Settings and project recovery
- Calculate actual cache usage instead of relying on a potentially stale displayed value.
- Ensure cache cleanup preserves project media, autosave and Room database files.
- Add recovery tests for missing, malformed and valid autosave snapshots.
- Add a confirmation step before replacing an open project with a recovered snapshot.

## Execution order

1. Audio export correctness and multi-clip PCM mixer.
2. Audio DSP model + preview processors.
3. EQ, Noise Gate, Compressor, Reverb, Delay and Limiter UI controls.
4. Effects preview/export parity and regression tests.
5. Audio Studio live preview, ruler and waveform accuracy.
6. Settings cache accounting and recovery hardening.
7. Full UI action smoke tests across Home, Projects, Import, Studio, Audio Studio, Effects Lab, Keyframe Editor, Settings and Module Center.

## Acceptance criteria

- No visible control is a silent no-op.
- Unsupported processing is visibly disabled or explicitly marked unavailable; it must never pretend to apply.
- Project edits survive app restart.
- Undo/redo restores every edit that is presented as undoable.
- Preview and export produce matching timing, transforms, effects and audio behavior.
- CI runs unit tests and builds the debug APK after each focused change.
- Work stays on `stabilize/android-build`; do not merge to `main` without explicit user approval.

## Current verification notes

- GitHub Actions run #43 passed on the stabilization branch before the latest button audit change.
- The Reset action fix is committed separately and its CI run must complete before it is treated as verified.
- Passing CI proves compilation/tests in CI only; it does not replace on-device playback, export and UI smoke tests.
