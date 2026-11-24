# PocketAgent development roadmap

This roadmap separates the work into independent workstreams so UI, model/runtime, input modalities, and quality can be developed and verified separately. A multi-agent runner is not available in this workspace; the work is being tracked as parallel review streams rather than pretending separate agents were launched.

## Product principles

- The user remains in control. PocketAgent suggests; it never reads other apps' screens or executes taps, typing, gestures, launches, or settings changes.
- Be honest about the selected model's capabilities. The current Gemma 3 1B LiteRT-LM file is text-oriented; a polished attachment picker must not imply that it can understand images or arbitrary audio.
- Keep local-first behavior clear. Speech recognition may use the device's configured provider; ask for microphone permission only when the user chooses dictation.
- Keep attachment contents user-selected, bounded, and visible to the user before use.

## Workstreams

### A — Product design and interaction
- [x] Replace the bare form with a structured, responsive-feeling assistant screen, clear hierarchy, branded color palette, model state, quick prompts, and readable suggestion card.
- [x] Add copy, share, and text-to-speech actions for generated guidance.
- [ ] Add accessibility review (TalkBack labels, font scaling, contrast, touch targets) and small-screen/landscape verification.

### B — Model runtime and answer quality
- [x] Improve prompt guidance for concise manual steps, untrusted attachment text, assumptions, and safety reminders.
- [x] Surface model readiness and import progress; reset the engine when the model is replaced.
- [ ] Verify model compatibility/error cases on real Android hardware and improve long-inference cancellation/progress handling.
- [ ] Add model metadata/capability detection before presenting image/audio understanding as supported.

### C — Input modalities and attachments
- [x] Typed text input and device speech-to-text dictation.
- [x] Import one or more selected plain text, Markdown, or CSV files as bounded prompt context.
- [ ] Add image attachments with actual on-device OCR/vision support; a text-only model must not be described as vision-capable.
- [ ] Add audio-file ingestion/transcription (separate from live dictation) with an explicitly chosen on-device or privacy-reviewed provider.
- [ ] Evaluate PDF/document extraction, clipboard/paste, camera capture, and share-to-PocketAgent flows.

### D — Reliability, privacy, and release quality
- [x] Bound text attachment context to 12,000 characters and allow removal before generation.
- [x] Document current capabilities and unsupported modalities.
- [ ] Add automated tests for import, permission denial, missing model, empty response, attachment size, and inference failure.
- [ ] Run build/lint on CI and perform real-device QA before release.

## Current milestone

**Milestone 1 — Better daily-use experience:** UI refresh, task dictation, readable local model state, plain-text attachment context, and answer copy/share/read-aloud actions. This does not yet provide image understanding or audio-file transcription. The default recommended Gemma 3 1B INT4 model should be treated as text-first; further modalities need model/runtime capability work rather than UI-only promises.
