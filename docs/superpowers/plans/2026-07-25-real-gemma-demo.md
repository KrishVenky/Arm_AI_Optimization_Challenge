# Real Gemma Demo Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to execute this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This plan has no new source code or unit tests — it is an operational/verification runbook (build, install, push a real model, verify against live logcat/device state). Each step still has an exact command and an exact expected result; "verify X" steps replace "run the test suite" steps from the code-writing template.

**Goal:** Get the real Audimus app running its actual `GemmaCallAnalyzer` (LiteRT-LM + Gemma 4 E2B) path on the Pixel 9a, and produce a screen recording of it classifying a simulated scam call, for use as authentic demo footage.

**Architecture:** No code changes. Build the existing app, install it, download and push the official `gemma-4-E2B-it.litertlm` model to the exact path `GemmaCallAnalyzer` already expects, grant the permissions the app's onboarding flow would normally collect interactively, confirm via logcat that the real analyzer (not the keyword stub) initialized, then drive the app's existing "simulate an incoming call" debug feature and capture the result.

**Tech Stack:** Gradle (`gradlew`), adb (`C:\Users\krish\AppData\Local\Android\Sdk\platform-tools\adb.exe`), Pixel 9a over USB (device id `56141JEBF19130`).

## Global Constraints

- No code changes to `AnalysisPipeline.kt`, `GemmaCallAnalyzer.kt`, `CallAnalyzer.kt`, or any other app source file.
- Model file must be placed with the exact name `gemma-4-E2B-it.litertlm` at `/sdcard/Android/data/com.audimus/files/models/` — `GemmaCallAnalyzer.kt:85-86,307-308` constructs this path from `MODELS_DIR = "models"` and `MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"` against `context.getExternalFilesDir(...)`, so any other name or path silently fails init and falls back to the stub.
- No em dashes in any output this plan produces (commit messages, notes).
- adb connection has been intermittently dropping to `offline` this session under sustained load (see prior benchmark work) — restart the adb server (`adb kill-server && adb start-server`) before any step if a command returns `device offline` or `no devices/emulators found`, then retry that step.

---

### Task 1: Build and install the app

**Files:** none created or modified.

**Interfaces:**
- Produces: an installed `com.audimus` package on the Pixel 9a, confirmed via `pm list packages`.

- [ ] **Step 1: Build and install in one command**

Run:
```
cd /c/Users/krish/Documents/VSCode/Arm && ./gradlew installDebug
```
Expected: `BUILD SUCCESSFUL` at the end of output. If it fails, capture the exact error — do not guess a fix blind; read the Gradle error output and address the specific failure (this build has never been exercised this session, so a failure here is real information, not a fluke to retry past).

- [ ] **Step 2: Verify the package is installed**

Run:
```
/c/Users/krish/AppData/Local/Android/Sdk/platform-tools/adb.exe shell pm list packages | grep com.audimus
```
Expected output: `package:com.audimus`

---

### Task 2: Download the real Gemma 4 E2B LiteRT-LM model

**Files:** none in the repo — downloads to a local scratch path outside the repo (e.g. `C:\Users\krish\Documents\VSCode\llama.cpp\bench-models\` or a new sibling folder), since this is a multi-gigabyte binary artifact, same pattern as the benchmark models.

**Interfaces:**
- Produces: a local file `gemma-4-E2B-it.litertlm`, exactly 2,588,147,712 bytes.

- [ ] **Step 1: Download with progress bar disabled**

(The progress bar in `Invoke-WebRequest` caused a ~50x slowdown earlier this session — always disable it for large downloads.)

Run in PowerShell:
```powershell
$ProgressPreference = 'SilentlyContinue'
$outDir = "C:\Users\krish\Documents\VSCode\llama.cpp\bench-models"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$outFile = "$outDir\gemma-4-E2B-it.litertlm"
Invoke-WebRequest -Uri "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm" -OutFile $outFile
(Get-Item $outFile).Length
```
Expected: final printed length is `2588147712`. If it does not match, the download is corrupt or incomplete — re-download rather than proceeding.

---

### Task 3: Push the model to the device and verify placement

**Files:** none in the repo.

**Interfaces:**
- Consumes: the local file from Task 2 (`gemma-4-E2B-it.litertlm`, 2,588,147,712 bytes), the installed package from Task 1.
- Produces: `/sdcard/Android/data/com.audimus/files/models/gemma-4-E2B-it.litertlm` on-device, verified byte-identical.

- [ ] **Step 1: Create the models directory on-device**

Run:
```
ADB="/c/Users/krish/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" shell mkdir -p "//sdcard/Android/data/com.audimus/files/models"
```
(Use the leading `//` on the remote path — a single leading `/` gets mangled into a Windows path by Git Bash's MSYS path conversion, as seen repeatedly during the benchmark work this session.)

- [ ] **Step 2: Push the model**

Run:
```
"$ADB" push "/c/Users/krish/Documents/VSCode/llama.cpp/bench-models/gemma-4-E2B-it.litertlm" "//sdcard/Android/data/com.audimus/files/models/gemma-4-E2B-it.litertlm"
```
Expected: `1 file pushed` with no errors.

- [ ] **Step 3: Verify on-device size matches**

Run:
```
"$ADB" shell ls -la "//sdcard/Android/data/com.audimus/files/models/"
```
Expected: a line for `gemma-4-E2B-it.litertlm` with size `2588147712`.

---

### Task 4: Grant permissions and enable the accessibility service

**Files:** none.

**Interfaces:**
- Consumes: installed package `com.audimus` (Task 1).
- Produces: `POST_NOTIFICATIONS` granted, `SYSTEM_ALERT_WINDOW` allowed, `AudimusAccessibilityService` enabled — all three verified via adb, not assumed.

- [ ] **Step 1: Grant notification permission**

Run:
```
"$ADB" shell pm grant com.audimus android.permission.POST_NOTIFICATIONS
```
Expected: no output on success (silent). Verify with:
```
"$ADB" shell dumpsys package com.audimus | grep POST_NOTIFICATIONS
```
Expected: line containing `granted=true`.

- [ ] **Step 2: Allow the overlay (draw-over-other-apps) permission**

Run:
```
"$ADB" shell appops set com.audimus SYSTEM_ALERT_WINDOW allow
```
Verify:
```
"$ADB" shell appops get com.audimus SYSTEM_ALERT_WINDOW
```
Expected: contains `allow`.

- [ ] **Step 3: Enable the accessibility service**

Run:
```
"$ADB" shell settings put secure enabled_accessibility_services com.audimus/com.audimus.service.AudimusAccessibilityService
"$ADB" shell settings put secure accessibility_enabled 1
```
Verify:
```
"$ADB" shell settings get secure enabled_accessibility_services
```
Expected: contains `com.audimus/com.audimus.service.AudimusAccessibilityService`.

**If this silently fails to take effect** (the service does not actually start — checked in Task 5), this Android version may require a manual tap through Settings > Accessibility on the phone itself. Do not fight this via more adb commands if the first attempt does not stick — tell the user and ask them to enable it manually, then continue.

---

### Task 5: Launch the app and verify the real Gemma analyzer initialized

**Files:** none.

**Interfaces:**
- Consumes: model placed (Task 3), permissions granted (Task 4).
- Produces: confirmation, from live logcat, that `GemmaCallAnalyzer.initialize()` returned `true` and the pipeline selected it over the stub.

- [ ] **Step 1: Clear old logcat buffer and launch the app**

Run:
```
"$ADB" logcat -c
"$ADB" shell am start -n com.audimus/.MainActivity
```
Expected: `Starting: Intent { ... cmp=com.audimus/.MainActivity }`

- [ ] **Step 2: Watch logcat for the Gemma-ready status line**

Run (wait a few seconds for model load, then run once — do not tail indefinitely):
```
sleep 15
"$ADB" logcat -d | grep -i "gemma\|model not found\|keyword analyzer"
```
Expected: a line matching `Gemma 4 E2B ready` (from `AnalysisPipeline.kt:77`). If instead you see `Using keyword analyzer (push the Gemma model for full reasoning)` or `Model not found`, the real analyzer did not initialize — check the exact file path and permissions from Tasks 3-4 before proceeding, do not continue to Task 6 on the stub path.

---

### Task 6: Run the simulated call and confirm real classification

**Files:** none.

**Interfaces:**
- Consumes: app running with real Gemma analyzer active (Task 5).
- Produces: an on-screen HIGH or MEDIUM risk classification with a reason string, sourced from the real model's JSON output (not keyword matching).

- [ ] **Step 1: Screenshot the current screen to find the "simulate an incoming call" control**

Run:
```
"$ADB" exec-out screencap -p > /c/Users/krish/AppData/Local/Temp/claude/screen1.png
```
Read the resulting PNG (via the Read tool) to locate the "simulate an incoming call" tile (`HomeScreen.kt:469-473`) and the transcript text input (`HomeScreen.kt:375`) on screen, since exact pixel coordinates depend on the live layout and cannot be hardcoded in this plan.

- [ ] **Step 2: Tap "simulate an incoming call"**

Run (substitute the real coordinates found in Step 1):
```
"$ADB" shell input tap <X> <Y>
```

- [ ] **Step 3: Enter a scam-shaped transcript and confirm consent**

Type a transcript through the debug text input that should trigger a HIGH classification per the prompt rules already in `GemmaCallAnalyzer.kt:186-191` (hard credential/payment ask + urgency/authority impersonation), e.g.:
```
"$ADB" shell input text "This is your bank calling. Your account has been compromised, we need your one time password right now to secure it or your funds will be frozen."
```
Then tap the consent "yes" control per the on-screen consent prompt (screenshot again if needed to find it), since `AnalysisPipeline` gates all analysis on `CONSENTED` state.

- [ ] **Step 4: Wait for a real analysis pass and screenshot the result**

The pipeline polls every 4 seconds (`ANALYSIS_INTERVAL_MS`, `AnalysisPipeline.kt:41`) and a real Gemma pass is not instant. Wait at least 15 seconds, then:
```
sleep 15
"$ADB" exec-out screencap -p > /c/Users/krish/AppData/Local/Temp/claude/screen2.png
```
Read the resulting PNG. Expected: a risk banner (HIGH or MEDIUM) with a reason string visible on screen, not the default LOW/no-risk state.

- [ ] **Step 5: Confirm the classification came from the real model, not the stub, via logcat**

Run:
```
"$ADB" logcat -d | grep -i "analysis failed\|riskLevel\|GemmaCallAnalyzer"
```
Confirm no fallback/error messages appear and the timing is consistent with a real model pass (not instant keyword matching).

---

### Task 7: Capture a screen recording for the demo video

**Files:** none in the repo — output video saved locally for the user to use in video editing.

**Interfaces:**
- Consumes: working end-to-end flow confirmed in Task 6.

- [ ] **Step 1: Start recording, then repeat the simulated-call flow live**

Run:
```
"$ADB" shell screenrecord --time-limit 60 "//sdcard/demo_capture.mp4"
```
While this is recording (it blocks for up to 60 seconds or until stopped), repeat the tap/text-input/wait sequence from Task 6 so the recording captures the real flow live.

- [ ] **Step 2: Pull the recording to the dev machine**

Run:
```
"$ADB" pull "//sdcard/demo_capture.mp4" "C:\Users\krish\Documents\VSCode\Arm\demo_capture.mp4"
```
Note: do not commit this video file to the git repo — it is a large binary artifact for the user's video editing workflow, not source. Deliver it to the user directly (e.g. via SendUserFile) instead.

---

## Self-review notes

- Spec coverage: all 7 design steps map 1:1 to Tasks 1-7 above.
- No placeholders: every step has an exact command; Task 6's pixel coordinates are the one necessarily-live-discovered value, called out explicitly rather than hidden.
- Type/name consistency: `com.audimus` package id, `AudimusAccessibilityService` full class name, `gemma-4-E2B-it.litertlm` filename, and the exact model directory path are used identically across Tasks 1, 3, 4, and 5 (cross-checked against `AndroidManifest.xml` and `GemmaCallAnalyzer.kt` directly, not assumed).
- Scope: single cohesive deliverable (one working demo recording), not split further.
