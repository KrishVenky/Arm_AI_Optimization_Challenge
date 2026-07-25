# Design: Get the real on-device Gemma pipeline demoable

## Context

The Arm hackathon submission needed two pieces of evidence, both explained in the top-level `CLAUDE.md`: a benchmark proving KleidiAI/quantization gives a real speedup on this exact chip, and a working app to record demo footage of. The benchmark work (llama.cpp cross-compiled with KleidiAI, Gemma 3 1B-it quantized to F16/Q8_0/Q4_0, measured on-device) is complete and lives entirely outside the app, run as a standalone CLI tool over adb.

Nothing in `app/` was touched to get there. The Audimus app itself has never been built or installed on the Pixel 9a this session, and `GemmaCallAnalyzer` has never had a real model to load — every classification this session, if any occurred, would have gone through `StubCallAnalyzer`'s keyword fallback, not Gemma.

Since the deliverable is a video + write-up (no judge operates the app live), the two pieces don't need to be technically the same running system. But the write-up still needs to be honest about what's real: if it shows the app "working," the app needs to actually be running its real Gemma pipeline, not the stub.

## Goal

Get the actual Audimus app, running its real `GemmaCallAnalyzer` path (not the stub), classifying a simulated scam call end-to-end on the Pixel 9a — recordable as authentic demo footage. No new code, no architecture changes — this is entirely "make the already-built feature actually run with real data."

## Non-goals

- No JNI/llama.cpp integration into the app (decided against — see conversation: two honestly-separate demo segments instead of one integrated system).
- No live phone call / real captioning test (decided against for this pass — the app's existing "simulate an incoming call" + transcript-input debug path is a real, shipped feature and sufficient for demo footage).
- No code changes to `AnalysisPipeline`, `GemmaCallAnalyzer`, or any other app source.

## Steps

1. **Build & install.** `gradlew installDebug` (or `assembleDebug` + `adb install`). First time the app itself has been built this session — treat a clean build as unverified until proven.
2. **Model acquisition.** Download `gemma-4-E2B-it.litertlm` (2,588,147,712 bytes / 2.41 GiB) from `litert-community/gemma-4-E2B-it-litert-lm` on Hugging Face — Google's official LiteRT-LM conversion org, `base_model: google/gemma-4-E2B-it`, apache-2.0. This is the exact filename `GemmaCallAnalyzer.MODEL_FILE_NAME` already expects.
3. **Model placement.** `adb push` to `/sdcard/Android/data/com.audimus/files/models/gemma-4-E2B-it.litertlm` (package id confirmed as `com.audimus` from `app/build.gradle.kts`). Requires the app to be installed first so the directory exists (or create it manually).
4. **Onboarding/permissions.** Grant `POST_NOTIFICATIONS`, enable the accessibility service, grant the `SYSTEM_ALERT_WINDOW` overlay permission. Accessibility service may require a manual tap on-device even via `adb shell settings put secure enabled_accessibility_services` on some Android versions — flag to the user if so rather than fighting it via adb.
5. **Verify the real Gemma path is active.** Check logcat / `ProtectionState` status line for `"Gemma 4 E2B ready"` (success) vs the stub fallback message (`"Using keyword analyzer..."`) to confirm `GemmaCallAnalyzer.initialize()` actually succeeded against the real file, not that the model file merely exists.
6. **Run the built-in simulated call.** Tap "simulate an incoming call" (`AudimusBridge.pipeline?.startSimulatedCall()`), feed a scam-shaped transcript via the existing debug text input (`AudimusBridge.pipeline?.appendUtterance(...)`), grant consent through the existing consent flow, and confirm a real risk classification (HIGH/MEDIUM), reason string, and overlay/vibration response come back from the actual on-device Gemma model.
7. **Capture.** Screen-record the working flow (`adb shell screenrecord` or equivalent) as raw footage for the demo video.

## Risks / open questions

- Gradle build may not be clean on first attempt — this session has never exercised it. Treat as genuinely unverified.
- Accessibility service auto-grant via adb may not work on this Android version (17) and could need a manual tap.
- `GemmaCallAnalyzer.initialize()` may be slow (comment in source says "may take several seconds" for a multi-GB model) — expect a real wait, not necessarily a hang.
- If Gemma 4 E2B's `.litertlm` inference is itself slow enough to look bad on camera, that's still an honest result worth knowing before recording rather than after.
