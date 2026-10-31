# VEX EDIT STUDIO — Button Functionality Audit

Branch: `stabilize/android-build`

This document tracks whether visible controls perform real project operations. A control is not considered complete merely because it responds to a tap.

## Completed in this pass

| Screen | Control | Status | Implementation |
|---|---|---|---|
| Effects Lab | Save Preset | Implemented | Saves named effect settings locally; saved presets can be loaded and deleted |
| Effects Lab | Category filters | Implemented | Filters the list by supported effect categories |
| Effects Lab | Effect preview | Improved | Preview uses the same color-matrix formulas as the currently supported export effects |
| Audio Studio | Mixer faders | Implemented | Faders update the selected project's audio-track gain |
| Audio Studio | Mute / Solo | Implemented | Mute and Solo now update persistent project track state |
| Audio Studio | Clip Volume | Implemented | Updates clip gain and timeline preview |
| Audio Studio | Fade In / Fade Out | Preview implemented | Stores fade durations and applies a gain envelope during timeline playback; export DSP is still pending |
| Keyframe Editor | Previous / Next | Implemented | Jumps to the closest previous/next keyframe for the selected clip |
| Keyframe Editor | Add Keyframe | Improved | Captures Position X/Y, Scale, Rotation and Opacity at the playhead |
| Studio | Crop ratio presets | Partial | Presets update the project canvas aspect ratio and support undo; this is not yet a pixel-accurate crop/transform editor |
| Settings | Language / Performance | Implemented | Preferences persist across app restarts |
| Settings | Clear Cache | Implemented | Deletes app cache files and reports failure when rendering is active or files cannot be removed |
| Settings | Restore Auto-Save | Implemented | Success is only shown when a valid recovery snapshot was restored |

## Remaining work — prioritized

### P0 — Audio DSP and export parity
- Implement EQ, Noise Gate, Compressor, Reverb, Delay and Limiter in the live preview engine.
- Apply the same processing to exported audio; do not claim success if only preview processing works.
- Mix all overlapping audio clips instead of selecting only one audio source during muxing.
- Apply clip volume, track fader, mute/solo and fade envelopes to the final exported audio.
- Replace informational placeholders in Audio Studio with real controls only as each processor becomes available.

### P1 — Effects renderer parity
- Implement Film Grain, RGB Split, Motion Blur, Chromatic Aberration and VHS in the renderer.
- Add preview/export parity tests for each effect.
- Keep unsupported effects out of the selectable list until their renderer exists.

### P1 — Keyframe timeline
- Make keyframe diamonds selectable and draggable.
- Add/delete selected keyframes and edit their interpolation per keyframe.
- Make Transform, Motion, Opacity and Curves tabs change the visible editor controls.
- Preview all animated values using the same evaluator as export.

### P1 — Crop and transform
- Add a real crop rectangle with draggable edges/corners.
- Persist crop bounds per clip and render them identically in preview/export.
- Keep project canvas aspect ratio separate from clip crop.

### P2 — Timeline and preview interactions
- Replace fixed ruler labels with a timebase derived from project duration and zoom.
- Make timeline scrubbing and audio clip selection time-accurate.
- Make preview fullscreen a real action.
- Ensure trim, split, drag, snapping and ripple behavior remain consistent across video and audio tracks.

### P2 — Settings and project lifecycle
- Add actual theme selection only when theme tokens are dynamic across all screens.
- Make auto-save status reflect the repository's real recovery snapshot.
- Calculate cache size from files instead of relying on a placeholder counter.

### P3 — Module Center
- Audit module downloads, install verification, enable/disable and removal.
- Keep SDK/plugin execution isolated and postpone the full extensible SDK system until the core editor is stable.

## Verification rules
- Run unit tests and `:app:assembleDebug` after each focused change.
- Add regression tests for keyframe navigation, audio gain/fades, project settings persistence and aspect-ratio undo.
- Test on a physical Android device before marking playback, DSP or export features fully complete.
- Keep all changes on `stabilize/android-build`; do not merge into `main` without explicit approval.
