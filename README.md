# Audimus

An on-device AI call assistant built for the Indian context, designed to protect people when they're at their most vulnerable — on a phone call.

Built at the **Google DeepMind Bangalore Hackathon 2026**, hosted by Google DeepMind and Cerebral Valley.

## What it does

- **Real-time scam protection** — analyzes the live call transcript on-device and warns you mid-call when the conversation shows signs of a scam.
- **Follow-up capture** — detects meetings and action items mentioned during a call, then shows an editable review sheet when the call ends so you can confirm them into your calendar and tasks.
- **Privacy-first** — everything runs locally on the phone using Gemma. No audio is recorded, and nothing ever leaves the device.

## How it works

Audimus reads the on-screen transcript produced by your phone's captioning app via an accessibility service and runs it through an on-device Gemma model to classify risk and extract follow-ups. Results surface as a lightweight overlay during the call and a review sheet afterwards.

## Tech stack

- Kotlin + Jetpack Compose
- Room for local storage
- Gemma running fully on-device
- Android accessibility services for transcript capture

## Team

Built by Krishna Venkatesh and Kaushik Saravanan.
