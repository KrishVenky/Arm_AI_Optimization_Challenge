# Audimus: Design

Audimus is an on-device scam-call shield for Android. It reads the live transcript of a phone call
from a captioning app already running on the phone, reasons over the conversation with an on-device
LLM (Gemma 4 E2B via LiteRT-LM), warns the user when a call looks like a scam, and quietly captures
any meetings and tasks mentioned. **No audio, transcript, or reasoning ever leaves the phone**, and
there is no `INTERNET` permission in the manifest, so Audimus itself never makes a network call.
Detected meetings are written only to a local, account-less calendar (never a synced one), so they
cannot leave the device through calendar sync either.

## Why this is an accessibility service

Audimus does not capture audio at all. It reads the on-screen transcript text produced by a
captioning app the user already has running (Live Transcribe, Pixel Live Caption, or a captions
overlay in Chrome). Reading another app's on-screen text is the standard, benign use of an
accessibility service, the same capability a screen reader uses: transcription apps render their
output as real text views, so the words are exposed through the accessibility node tree
(`AccessibilityNodeInfo.getText`). The warning overlay drawn on top of the in-call screen is a
separate capability: it uses `TYPE_APPLICATION_OVERLAY` and requires the user to separately grant the
draw-over-other-apps permission during onboarding, it does not come free with the accessibility
service.

### Honest scope and limits

- **A captioning app must already be running and visible on screen.** Audimus does not transcribe
  anything itself; it reads whichever of `TRANSCRIPT_SOURCES` (Live Transcribe, Pixel Live Caption,
  or Chrome captions) is currently on screen. If none is running, there is no transcript to read.
- **This is a sideloaded, research build.** It has not been submitted to or reviewed for the Google
  Play Store.
- **Everything is on-device.** Transcript scraping, reasoning (Gemma 4 E2B via LiteRT-LM), calendar
  writes, and task storage all run locally. Audimus itself has no `INTERNET` permission and makes no
  network call, and `CalendarWriter` only ever targets a local, account-less calendar, never falling
  back to some other (e.g. Google-synced) calendar that could sync an event off the device.

## End-to-end flow

The pipeline is owned by `AudimusAccessibilityService`, which feeds `AnalysisPipeline`, and all live
state is mirrored into the `ProtectionState` singleton, which the Compose UI and the overlay observe.

1. **Transcript scraping** (`TranscriptScraper`, `AudimusAccessibilityService`). The accessibility
   service listens for accessibility events from a whitelisted set of transcript sources: Live
   Transcribe, Pixel Live Caption, and Chrome captions. When one of those apps is on screen,
   `TranscriptScraper` walks its accessibility node tree and diffs the visible text at the word
   level against what it has already emitted, since caption windows scroll and revise their last
   line in place, so a naive "did the text grow" check would re-emit the whole visible block on
   every update. Only the genuinely new words are forwarded to the pipeline.

2. **Call start** (`AnalysisPipeline.appendUtterance`). The first transcript text that arrives, from
   a live caption source or typed into the on-screen demo simulator, starts a call session and kicks
   off the spoken consent handshake. There is no separate call-state detection: a call is treated as
   active for as long as new transcript text keeps arriving. Once the caller has consented, a call
   with no new transcript line for 9 seconds auto-ends; if the consent handshake never resolves to
   `CONSENTED` (the caller stays silent, or declines), the 9-second watchdog does not fire and the
   call stays open until the user ends it manually. Auto-ending moves to the follow-up review sheet
   only if a meeting or task was actually detected; otherwise the session just resets.

3. **Consent disclosure** (`ConsentController`). When a call starts, the app speaks a disclosure
   through the speaker with on-device `TextToSpeech`: *"This call may be analyzed by Audimus for scam
   protection. Do you consent? Please say yes or no."* The reply is classified from the existing
   transcript (a simple YES/NO keyword match, no LLM needed); anything that isn't a recognized yes or
   no phrase is left unresolved. `DECLINED`, or `NO_RESPONSE` after a 12 s timeout with no resolved
   answer, triggers a soft-stop: analysis halts and the UI shows that protection is paused because the
   caller did not consent. `CONSENTED` lets the pipeline proceed. (Actually hanging up the call on
   refusal needs a dialer/call-management role and is out of scope; Audimus stops listening only.)

4. **Local reasoning** (`GemmaCallAnalyzer`, LiteRT-LM + Gemma 4 E2B). Every 4 s, once consented, the
   whole running transcript (most recent 4,000 characters) is sent to Gemma in a single prompt with
   three extraction goals: (a) **scam risk**, reasoning over conversational context (urgency, spoofed
   authority, requests for OTP/PIN/bank details/gift cards/remote access), holding state across the
   call rather than judging each chunk in isolation; (b) **meeting mentions**, any proposed
   date/time/person; (c) **task mentions**, any explicit task and who it's for. Gemma returns one
   structured JSON object covering all three; output is parsed defensively and a malformed pass is
   logged and skipped, never crashing. A keyword `StubCallAnalyzer` stands in until the roughly 2.6 GB
   model file is pushed to the device, so the whole app is testable end-to-end without it.

5. **Risk classification and intervention.** The result carries `riskLevel` (LOW/MEDIUM/HIGH),
   `reason`, and `confidence`. HIGH triggers an escalating `Vibrator` waveform and a full-screen
   warning overlay drawn over the call showing the reason; MEDIUM gives a lighter buzz. The overlay is
   dismissible and de-escalates: if a later pass drops the risk, it clears rather than sticking.

6. **Calendar integration** (`CalendarWriter` / `CalendarRepository`). A detected meeting is inserted
   as a real event via `CalendarContract`, always into a local, account-less calendar
   (`ACCOUNT_TYPE_LOCAL`), creating one named "Audimus" if the device has none. `CalendarWriter` never
   falls back to some other writable calendar (for example a Google-synced one), so an inserted event
   cannot leave the device through calendar sync. The other party's name (as spoken) goes in the
   description, since we can't resolve their real account. Each insert is also recorded in Room so the
   app can list the events it created.

7. **Task tracking** (`TaskRepository`, Room). A detected task is written to a local Room database:
   task text, assignee (free text, as named on the call), source call, timestamp. Persists across
   restarts.

8. **Home ecosystem screen** (`HomeScreen`). A Compose dashboard with a bottom nav: **Protection**
   (live service/call/consent status, live transcript, and the current risk banner), **Calendar**
   (events Audimus created), and **Tasks** (captured action items). First launch shows an onboarding
   checklist: grant permissions, enable the accessibility service, allow the overlay.

## Color palette

The palette commits to a calm, trustworthy "shield" feeling (deep teal rather than generic Material
blue) with warm, not stark, neutrals, and a clear, conventional risk scale so a warning reads
instantly.

### Light theme
| Role | Hex | Notes |
|------|-----|-------|
| Primary (accent) | `#0B4F4A` | Deep teal, the shield/trust colour |
| Surface / background | `#F5F3EE` | Warm off-white, softer than pure white |
| Surface variant | `#E6E2D8` | Warm card fill |
| On-surface | `#1C1B17` | Warm near-black text |

### Dark theme
| Role | Hex | Notes |
|------|-----|-------|
| Primary (accent) | `#1E8A7C` | Lighter teal, legible on dark |
| Surface / background | `#1A1A18` | Warm near-black, not stark #000 |
| Surface variant | `#2A2A26` | Raised warm card |
| On-surface | `#EAE7DF` | Warm off-white text |

### Risk & consent states
| State | Hex | Meaning |
|-------|-----|---------|
| High risk (both themes) | `#C4432B` | Warm coral-red, alarming without being a fire-engine red |
| Medium risk (dark theme) | `#D98E2B` | Amber, caution |
| Medium risk (light theme) | `#B26A12` | Darker amber, for contrast against the light surface |
| Safe / consent given (both themes) | `#2E7D5B` | Muted green-teal, harmonises with the teal primary |

## On-device inference benchmark (Arm KleidiAI)

Audimus's own runtime (above) uses Google's LiteRT-LM, which has its own internal quantization and
XNNPACK acceleration. Separately, to validate that Arm's KleidiAI-accelerated quantized kernels give
a real, usable speedup on this exact phone, we cross-compiled llama.cpp for arm64-v8a with KleidiAI
enabled and benchmarked Gemma 3 1B-it (a different, GGUF-compatible model, since llama.cpp does not
read the `.litertlm` format Audimus's own model ships in) directly on-device via `adb shell`.

This is a hardware/toolchain validation, not a claim about Audimus's own runtime speed: Audimus does
not run through llama.cpp or KleidiAI. It shows that quantized int8 inference is measurably faster on
this chip, and the benchmarked workload shape (a long prompt in, a short structured answer out)
mirrors how Audimus actually uses its model each analysis pass.

**Hardware:** Pixel 9a, Tensor G4. Tensor G4 does not support SME2, so these results reflect standard
KleidiAI/XNNPACK int8 (i8mm and dotprod) acceleration, not SME2-class numbers.

**Method:** llama.cpp built with `GGML_CPU_KLEIDIAI=ON`, `GGML_CPU_ARM_ARCH=armv8.7-a+dotprod+i8mm`.
F16, Q8_0, and Q4_0 variants were all derived from the same source weights with `llama-quantize`, then
benchmarked with `llama-bench` (default settings: `pp512`, `tg128`, 5 repetitions), with the phone
cooled to an idle baseline between each run to avoid thermal throttling skewing the comparison. Full
data, both the cooldown-separated clean run and the earlier throttled run, is in
[docs/BENCHMARKS.md](docs/BENCHMARKS.md).

| Format | Size on disk | Prompt processing | Token generation |
|---|---|---|---|
| F16 (baseline) | 1.87 GiB | 19.87 t/s | 4.39 t/s |
| Q8_0 | 1.00 GiB | 251.10 t/s | 4.13 t/s |
| Q4_0 | 687 MiB | 174.52 t/s | 3.87 t/s |

Q8_0 measured **12.6x** faster prompt processing than the F16 baseline, and Q4_0 measured **8.8x**
faster, on this exact chip. Token generation did not show a comparable speedup: Q8_0 and Q4_0's
token-generation numbers are statistically indistinguishable from F16's once thermal effects are
controlled for, consistent with single-token generation being memory-bandwidth-bound rather than
compute-bound on this workload. We are reporting this honestly rather than only the number that looks
best.

**Rationale.** Teal signals security and calm; keeping it as the primary (instead of Material blue)
makes the app feel purpose-built for protection. Warm neutrals reduce the clinical, high-contrast feel
of stark black/white and make long transcript reading easier on the eye. The risk trio is deliberately
conventional (red / amber / green-teal) so escalation is legible at a glance, but the red is a warmer
coral and the "safe" green is pulled toward teal so the whole set reads as one family rather than a
generic traffic light bolted onto the brand.
