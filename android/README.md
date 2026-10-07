# VisionMirror AI (Android)

Talking mirror for blind and low-vision people. Kotlin, Jetpack Compose, Hilt, CameraX, ML Kit. Audio and haptics are the interface; the screen is for low-vision users and sighted helpers.

API contract: [`../docs/api.md`](../docs/api.md). Privacy: [`../docs/privacy.md`](../docs/privacy.md). Release: [`RELEASE.md`](RELEASE.md). Demo: [`../docs/demo-script.md`](../docs/demo-script.md).

## Run

1. Copy `local.properties.example` to `local.properties`; set `sdk.dir` and `API_BASE_URL` (emulator: `http://10.0.2.2:8000/`; real phone: your LAN IP or deployed URL, trailing slash required).
2. Start the backend (`VISION_PROVIDER=mock` needs no API key).
3. `./gradlew :app:installDebug`, or open `android/` in Android Studio.

Tests: `./gradlew :app:testDebugUnitTest`.

## Flow

Onboarding (first launch only) -> **Mirror** -> **Analysing** -> **Result** (+ **Ask**) ; **Settings** from the Mirror. Debug builds also have the **Design Lab** (Settings > Developer).

```
app/src/main/java/ai/visionmirror/
  app/         AppNav (shared-element NavHost), VisionMirrorRoot, AppViewModel, HardwareKeys, Spoken (fixed lines)
  design/      tokens (colour, type, spacing, motion, theme), halo, icons, components
  audio/       SpeechManager (the one voice), EarconPlayer (synthesised), VoiceInput (recogniser), VoiceCommand
  haptics/     HapticsManager
  guidance/    FramingGuidance, MirrorCoach (throttle + stability): pure Kotlin, unit-tested
  camera/      CameraPreview (CameraX), FaceAnalyzer (ML Kit, on-device)
  imaging/     ImageProcessor (1280 px, JPEG 85, EXIF stripped)
  mirror/      MirrorViewModel + screen
  session/     SessionViewModel (describe + ask), Analysing and Result screens
  onboarding/  OnboardingViewModel + screen
  settings/    SettingsViewModel + screen
  data/        api (DTOs, Retrofit, AppError), auth (token flow), repo, settings (DataStore), net
  lab/         DesignLabScreen (debug kitchen sink)
```

## Design decisions worth knowing

- **Type:** Atkinson Hyperlegible Next (OFL), designed for low vision. Body >= 22 sp, headings >= 32 sp, tabular numerals for counters.
- **Themes:** Dark ("silvered glass at dusk"), Light ("paper at dawn"), System, plus a true High Contrast (black/white/yellow). `ContrastTest` checks every text/control pairing is >= 7:1 in all three.
- **Motion:** springs for everything spatial; animated values are read inside `graphicsLayer`/draw lambdas so nothing recomposes per frame. Reduce motion (app toggle or system animator scale 0) keeps feedback as brightness/opacity and removes movement.
- **Halo:** one persistent element that morphs between screens (`SharedTransitionLayout`). AGSL glow on API 33+, layered strokes below.
- **Large font scale:** segmented controls stack vertically at >= 150% so labels never clip.
- **Privacy:** photos live only in memory on the phone. The offline queue is memory-only too, so a photo waiting for a connection is lost if the app is killed. The server holds a session for 10 minutes for follow-ups and it is deleted on retake / leaving / "Delete session data". The spoken privacy line says exactly this, rather than "never stored" alone.
- **Speech pause:** Android TTS cannot pause, so `SpeechManager` stops and re-speaks from the current word; highlight ranges stay relative to the original text.
- **Earcons** are synthesised at start-up: no audio files to license.
- **Mirror guidance** targets: face ~20% of frame height, centred, centre ~32% down. Instructions: at most one per 1.5 s, never the same one back to back (a stuck instruction may repeat after 8 s). Auto-capture after 1.2 s of stable framing, with a cancellable 3-2-1 countdown.

## Manual verification (needs a real phone; cannot be done in CI)

**Design system / Phase 1**
1. Settings > Developer > Design Lab. Tap each Halo state; Ready should bloom and settle. Check smoothness on a mid-range phone.
2. Press and hold a button: it gives within ~90 ms, then springs back with slight overshoot. Tap twice quickly: no restart from zero.
3. Haptics section: each pattern feels distinct. Earcons: each clearly different, none harsh.
4. Toggle Reduce motion, High contrast, Light; drag Font scale to 200%: nothing clipped.

**Onboarding / Phase 2**
5. Fresh install: Halo animates in, welcome and privacy are spoken with the highlight following. Camera and mic are each explained aloud before the system dialog. Tapping anywhere continues. Deny the camera: it says what to do and stays put. Returning launch skips straight to the Mirror.
6. Back gesture from Settings and from Result: it should scrub the transition (predictive back) rather than cut.

**Mirror / Phase 3**
7. Move left/right/up/down, closer/further: spoken instructions match, are not repeated rapidly, haptic ticks speed up as you centre. Cover the camera: "I can't see your face".
8. Hold still when framed: tones, 3-2-1, capture. Tap during the countdown: cancels. Volume key captures. Check the on-screen oval matches what the guidance thinks is centred (the face box is measured against the full camera frame).

**Describe and Ask / Phase 4**
9. Result is read immediately; highlighted word follows the voice; Pause/Resume continues from the same word; Repeat; detail level re-describes.
10. Hold anywhere and ask a question: ripples follow your voice, answer is spoken and shown; ask a second question.
11. Airplane mode, take a photo: spoken offline message, then re-enable networking: it describes by itself. Dark photo (`MOCK_SCENARIO=dark`): advice is read, no outfit shown, retake offered.

**Polish / Phase 5**
12. TalkBack on: every control has a label; focus order top to bottom; the app's own voice does not fight TalkBack (use the visible buttons).
13. Settings: speech speed, voice, detail, haptics, theme, high contrast, reduce motion, delete session data, replay privacy.
