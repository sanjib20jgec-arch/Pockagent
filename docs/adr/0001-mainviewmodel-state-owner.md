# ADR 0001: MainViewModel as the chat state owner

- Date: 2026-11-27
- Status: Accepted
- Scope: `hardening/9of10`, version 1.6.1 (versionCode 13)

## Context

`MainActivity` owned a second set of store instances and a mutable, Android-bound copy of chat state while `MainViewModel` already provided an immutable `MainUiState`, observer API, checksum-aware startup check, and store mutation methods. The duplicate ownership made it possible for one path to save a session or memory while the other path reported stale state, and made the 4,391-line Activity hard to split.

## Decision

`MainViewModel` owns the shared UI-state snapshot and the three sovereign local stores (`SessionStore`, `UserMemoryStore`, `PersonalNotesStore`). `MainActivity` obtains those stores through the ViewModel, registers exactly one observer in `onCreate`, seeds `initModels()` (with `restoreWhisper = false`, because the Activity already restores the native Whisper context), and mirrors the observed composer text back into the Android input field. Composer edits and Stop are reported to the ViewModel; session, memory, and note views refresh the ViewModel before reading the shared stores.

Inference streaming is deliberately left in `MainActivity` for this task. Moving it before the controller extraction in the next task would require a full `MainUiState` renderer and would risk changing prompt or stream text byte-for-byte. Task 8 moves streaming behind `ChatGenerationController`; until then the ViewModel is the state owner and the Activity is the temporary Android-bound streaming executor.

## Alternatives considered

1. **Delete `MainViewModel` and keep the Activity as sole owner.** Rejected: it discards tested state transitions, the SHA-256 sidecar startup verification, and the migration seams needed for the architecture target.
2. **Route generation through `MainViewModel.suggestSteps()` immediately.** Rejected: the Activity has no state renderer yet, so users would see no streamed answer; the ViewModel would also start a second native engine alongside the Activity's engine.
3. **Wire the ViewModel ownership seam now, then extract controllers.** Accepted: it removes duplicate store instances and begins one-way state flow without changing generation output.

## Consequences

- Positive: one instance of each local store; state changes are observable; startup checksum rejection is exercised on-device; dialogs read refreshed state; Task 8 has one clear state owner to call.
- Negative: composer text has a guarded two-way sync until the controller extraction; generation still has a temporary Activity executor.
- Neutral: `initModels(restoreWhisper = false)` keeps the existing native Whisper restoration path unchanged and avoids two native contexts.
