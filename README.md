# Audimus

An on-device AI call assistant built for the Indian context, designed to protect people when they're at their most vulnerable: on a phone call.

Originally built at the **Google DeepMind Bangalore Hackathon 2026**, hosted by Google DeepMind and Cerebral Valley. Significantly updated for the **Arm Create: AI Optimization Challenge** (Mobile AI track): the app now runs its real on-device Gemma model end to end (previously only a keyword fallback was testable), and we measured real Arm KleidiAI quantization speedups directly on the target phone.

## The number

Quantizing an on-device LLM to int8 (Q8_0) and running it through Arm's KleidiAI-accelerated kernels measured **12.6x faster prompt processing** than an unaccelerated fp16 baseline, benchmarked directly on this app's target phone (Pixel 9a, Tensor G4, no SME2). Full methodology, raw data, and honest caveats (including where quantization did *not* help): [docs/BENCHMARKS.md](docs/BENCHMARKS.md).

This is a hardware/toolchain validation using llama.cpp, run alongside the app, not a measurement of Audimus's own runtime. See [DESIGN.md](DESIGN.md#on-device-inference-benchmark-arm-kleidiai) for how the two relate.

## What it does

- **Real-time scam protection**: analyzes the live call transcript on-device, using Gemma via Google's LiteRT-LM runtime, and warns you mid-call when the conversation shows signs of a scam, with a cited reason tied to the transcript, never a free-text guess.
- **Follow-up capture**: detects meetings and action items mentioned during a call, then shows an editable review sheet when the call ends so you can confirm them into your calendar and tasks.

No audio is recorded. Analysis runs entirely on the phone, with no server in the loop.

## How this compares to Truecaller

Truecaller flags spam by matching a caller's number against a crowdsourced cloud database built from users' contacts and call logs. Audimus flags a scam from the call content itself, entirely on the phone, with zero contacts or messages ever leaving the device, no server lookup, and a reason tied to the actual transcript for every flag rather than a community label.

## How it works

Audimus reads the on-screen transcript produced by a captioning app already running on your phone (Live Transcribe, Pixel Live Caption, or Chrome captions) via an accessibility service, and runs it through an on-device Gemma model to classify risk and extract follow-ups. Every flag cites the specific pattern that triggered it, for example "caller impersonates a bank, demands a one-time password", never an explanation the model invented after the fact. Results surface as a lightweight overlay during the call and a review sheet afterwards. Full design details: [DESIGN.md](DESIGN.md).

## Tech stack

- Kotlin + Jetpack Compose
- Room for local storage
- Gemma 4 E2B running fully on-device via Google's LiteRT-LM
- Android accessibility services for transcript capture
- llama.cpp and Arm KleidiAI for the standalone on-device quantization benchmark ([docs/BENCHMARKS.md](docs/BENCHMARKS.md))

## Setup

1. Clone this repo and build with `./gradlew installDebug`, or open it in Android Studio.
2. Download the Gemma 4 E2B LiteRT-LM model (`gemma-4-E2B-it.litertlm`, about 2.6 GB) from [litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm).
3. Push it to the app's storage: `adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.audimus/files/models/gemma-4-E2B-it.litertlm`.
4. Launch the app and grant the permissions it asks for (notifications, draw-over-apps, and the accessibility service). Use "Simulate an incoming call" on the home screen to try it without a live call.

Without the model file, the app falls back to a deterministic keyword matcher so the rest of the app is still testable.

## Team

Built by Krishna Venkatesh and Kaushik Saravanan.

## License

[MIT](LICENSE)
