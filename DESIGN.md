# Audimus — Design

Audimus is an on-device scam-call shield for Android. It listens to a phone call, transcribes it
locally, reasons over the conversation with an on-device LLM (Gemma 4 E4B via LiteRT-LM), warns the
user when a call looks like a scam, and quietly captures any meetings and tasks mentioned. **No audio,
transcript, or reasoning ever leaves the phone** — there is no `INTERNET` permission in the manifest.

## Why this is an accessibility service (the load-bearing design decision)

Audimmus needs to hear a live phone call. On a stock, non-root Android phone this is only possible
for two kinds of app:

1. A **privileged, platform-signed app** holding `CAPTURE_AUDIO_OUTPUT` (e.g. Google's own Phone
   app / Call Notes). A sideloaded app can never hold this.
2. An **accessibility service**.

Google's audio documentation (*Sharing audio input*) states it directly for the "voice call +
ordinary app" case: *"The call always receives audio. The app can capture audio if it is an
accessibility service. The app can capture the voice call if it is a privileged (pre-installed) app
with permission `CAPTURE_AUDIO_OUTPUT`."*

An **ordinary** app's microphone is silenced by the OS during a call (`MODE_IN_CALL`), which is why
a plain foreground-service approach fails on a Pixel. Running as an accessibility service is the
supported exemption that lets our capture survive the call. Audimus therefore *is* an accessibility
service; that also gives it, for free, the ability to read the caller label off the dialer UI and to
draw a warning overlay on top of the native in-call screen ("integrated into the OS").

### Honest scope and limits

- **The far side of the call is heard acoustically, through the speaker.** The accessibility
  exemption unlocks *capturing during a call*; it does not hand us the private earpiece downlink
  (only a platform-signed app gets that). So **speakerphone must be on** for the caller's voice to
  be transcribed. Audimus surfaces a prompt when a call is active without speaker.
- **This cannot ship on the Play Store.** Google Play policy (2022) prohibits using the Accessibility
  API to record call audio. Audimus is a sideloaded / research build. This is a policy limit, not a
  technical one.
- **Everything is on-device.** STT (Vosk), reasoning (Gemma 4 E4B via LiteRT-LM), calendar, and tasks
  all run locally. There is no network transport anywhere.

## End-to-end flow

The pipeline is owned by `AudimusAccessibilityService` → `ProtectionPipeline`, and all live state is
mirrored into the `ProtectionState` singleton, which the Compose UI and the overlay observe.

1. **Call detection** (`CallMonitor`). A `TelephonyCallback` reports idle / ringing / active. A
   1 Hz poll of `AudioManager.getCommunicationDevice()` tracks whether speakerphone is on. When a
   call is active without speaker, the UI prompts the user to enable it (we can't force routing — the
   call's audio session belongs to the dialer). The accessibility event stream also reads the caller
   name/number off the dialer window for labelling.

2. **Audio capture** (`AudioCaptureEngine`). On call-active the service enters a **microphone
   foreground state** and opens an `AudioRecord` on `VOICE_RECOGNITION` (16 kHz mono PCM16).
   `VOICE_RECOGNITION` is chosen deliberately over `VOICE_COMMUNICATION`, whose echo cancellation
   would strip the far-end voice arriving from the loudspeaker. The engine registers an
   `AudioManager.AudioRecordingCallback` and exposes `isClientSilenced()` — the definitive signal for
   whether the accessibility exemption is actually delivering audio on this device. Raw PCM chunks
   (100 ms) are pushed to a consumer.

3. **On-device STT** (`VoskTranscriber`). Vosk (`vosk-model-small-en-us-0.15`, ~40 MB, bundled in
   assets, fully offline) transcribes the PCM **in our process**. This matters: Android's system
   `SpeechRecognizer` runs recognition in a *separate* process that loses in-call mic arbitration and
   would be fed silence, so Audimus instead feeds the accessibility-captured PCM straight to an
   in-process engine via `Recognizer.acceptWaveForm`. Finalized utterances form a timestamped running
   transcript; partial hypotheses stream live.

4. **Consent disclosure** (`ConsentController`). When protection starts, the app speaks a disclosure
   through the speaker with on-device `TextToSpeech`: *"This call may be analyzed by Audimus for scam
   protection. Do you consent? Please say yes or no."* The reply is classified from the existing
   transcript (simple YES/NO/UNCLEAR keyword intent — no LLM needed). `DECLINED` or `NO_RESPONSE`
   (12 s timeout) → **soft-stop**: capture and analysis halt and the UI shows "protection disabled —
   no consent". `CONSENTED` → the pipeline proceeds. (Actually hanging up the call on refusal needs a
   dialer/call-management role and is out of scope; Audimus stops listening only.)

5. **Local reasoning** (`GemmaCallAnalyzer`, LiteRT-LM + Gemma 4 E4B). Every ~6 s, once consented,
   the whole running transcript is sent to Gemma in a single prompt with three extraction goals:
   (a) **scam risk** — reasoning over conversational context (urgency, spoofed authority, requests
   for OTP/PIN/bank details/gift cards/remote access), holding state across the call rather than
   judging each chunk in isolation; (b) **meeting mentions** — any proposed date/time/person;
   (c) **task mentions** — any explicit task and who it's for. Gemma returns one structured JSON
   object covering all three; output is parsed defensively and a malformed pass is logged and
   skipped, never crashing. A keyword `StubCallAnalyzer` stands in until the 3.6 GB model is pushed,
   so the whole app is testable end-to-end without it.

6. **Risk classification & intervention.** The result carries `riskLevel` (LOW/MEDIUM/HIGH),
   `reason`, and `confidence`. HIGH triggers an escalating `Vibrator` waveform and a full-screen
   warning overlay drawn over the call showing the reason; MEDIUM gives a lighter buzz. The overlay is
   dismissible and **de-escalates**: if a later pass drops the risk, it clears rather than sticking.

7. **Calendar integration** (`CalendarWriter` / `CalendarRepository`). A detected meeting is inserted
   as a real event via `CalendarContract` into a local, account-less calendar (created as
   `ACCOUNT_TYPE_LOCAL` "Audimus" if the device has none) — no Google account or network. The other
   party's name (as spoken) goes in the description, since we can't resolve their real account. Each
   insert is also recorded in Room so the app can list the events it created.

8. **Task tracking** (`TaskRepository`, Room). A detected task is written to a local Room database:
   task text, assignee (free text, as named on the call), source call, timestamp. Persists across
   restarts.

9. **Home ecosystem screen** (`HomeScreen`). A Compose dashboard with a bottom nav: **Protection**
   (live service/call/consent status, the capture level + silencing readout, live transcript, and the
   current risk banner), **Calendar** (events Audimus created), and **Tasks** (captured action items).
   First launch shows an onboarding checklist: grant permissions → enable the accessibility service →
   allow the overlay.

## Color palette

The palette commits to a calm, trustworthy "shield" feeling — deep teal rather than generic Material
blue — with warm (not stark) neutrals, and a clear, conventional risk scale so a warning reads
instantly.

### Light theme
| Role | Hex | Notes |
|------|-----|-------|
| Primary (accent) | `#0B4F4A` | Deep teal — the shield/trust colour |
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

### Risk & consent states (both themes)
| State | Hex | Meaning |
|-------|-----|---------|
| High risk | `#C4432B` | Warm coral-red — alarming without being a fire-engine red |
| Medium risk | `#D98E2B` | Amber — caution |
| Safe / consent given | `#2E7D5B` | Muted green-teal, harmonises with the teal primary |

**Rationale.** Teal signals security and calm; keeping it as the primary (instead of Material blue)
makes the app feel purpose-built for protection. Warm neutrals reduce the clinical, high-contrast feel
of stark black/white and make long transcript reading easier on the eye. The risk trio is deliberately
conventional (red / amber / green-teal) so escalation is legible at a glance, but the red is a warmer
coral and the "safe" green is pulled toward teal so the whole set reads as one family rather than a
generic traffic light bolted onto the brand.
