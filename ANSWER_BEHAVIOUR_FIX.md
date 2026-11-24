# Why PocketAgent answered with "go to Google Maps" — and the fix

## The bug in one sentence

The app was not failing to answer. It was **instructing the model to refuse to answer**.

`MainActivity.kt` (line 58) and `MainUiState.kt` (line 61) hard-coded one system
instruction, sent with *every* request:

> "You are PocketAgent, a local step-by-step assistant. The user will describe a
> task but you cannot see or control their device. **Give concise, complete
> numbered instructions (usually 3–5 steps) for the user to carry out manually**,
> with no unnecessary preamble."

That is the whole problem. Ask "what is the distance from Jalpaiguri to Delhi?"
and the model is being told, by the app, that its job is to hand you a to-do list
rather than a fact. So the only compliant reply is "open Google Maps, pick two
points, read the distance." The model was doing exactly what it was told.

Two things made it worse:

| Issue | Effect |
| --- | --- |
| `MAX_RESPONSE_TOKENS = 256` | Even a real answer got cut off mid-sentence, which reads as another non-answer. |
| No conversation history in the prompt | A follow-up ("and by train?") arrived with no context, so it was treated as a brand-new "how do I…" task. |

## Why "just use a better prompt" is not the whole fix

A single instruction cannot serve both jobs. Some requests genuinely want steps
("how do I enable developer options?"). Most do not. The app needs to know which
kind of request it is holding, and shape the answer accordingly.

## What I changed

### 1. Intent-aware answering — `AnswerDirective.kt` (new)

Every request is classified into one of three shapes:

```kotlin
enum class Intent { FACT, PROCEDURE, LIVE_DATA }
```

* **FACT** — knowledge, figures, definitions, calculations, comparisons, drafts,
  summaries, translations. → *Answer it.* The closing instruction is literally
  "Do not tell the user to look it up."
* **PROCEDURE** — a real thing you do on your own phone or account
  ("enable developer options", "reset Wi-Fi"). → Numbered steps, and each step
  must contain the actual figure/draft/decision rather than "work it out".
* **LIVE_DATA** — weather, prices, live scores, train running status. → One
  sentence admitting the app is offline, then the best durable figures, marked
  approximate.

Ordering is deliberate: *"How far is Kolkata from Mumbai by train?"* mentions
travel but is a fact, so it stays a fact. The default fallback is now `FACT` —
defaulting to `PROCEDURE` was the original sin.

The system instruction also now explicitly forbids the exact failure mode:

> Never answer a question by sending the user off to look it up. Do not reply
> with instructions such as "open Google", "search the web", "go to Google Maps
> and measure the distance"… If you know the answer, state it.

All previous safety invariants are preserved (no screen access, no device
control, no password/PIN/OTP requests, attachments treated as untrusted).

### 2. One prompt builder for both code paths — `AnswerPromptComposer` / `HistoryPolicy`

The prompt was duplicated in `MainActivity` and `MainViewModel`, and had already
drifted apart. There is now a single composer that both use, which:

* injects real multi-turn history, so follow-ups work;
* marks attachments with the existing untrusted-content boundary;
* never sends the pending user turn twice (regenerate used to duplicate it).

### 3. A quality gate with one corrective retry

If the model still dodges — the reply only says "open Google and check" and
delivers no figure — the app retries **once** with:

> "Your previous reply only told the user how to find the answer elsewhere.
> Answer the request directly now… put the actual result in the first sentence."

Procedures are exempt, since telling you to open a system app is correct there.

### 4. Room to actually answer

`MAX_RESPONSE_TOKENS` raised 256 → 1024, and the context budget defaults to
4096 / 1024 / 400 to match.

### 5. UI no longer frames everything as chores

`Suggest steps` → **`Ask`**, `Your next steps` → **`Answer`**, and the hero copy
now reads "Ask anything. Get the actual answer, computed on this device."

## Pre-existing bugs found while verifying

The tree **did not compile** before this change. Fixed:

* Duplicate `isGenerating` declaration in `MainUiState`.
* `ChatMessage.user()` / `.assistant()` / `.system()` and `.isUser` /
  `.isAssistant` were used everywhere but never defined.
* `MainViewModel.updateTaskInput()` was called but only `onTaskInputChanged()`
  existed.
* `MainViewModelTest` set a fake field (`responseToReturn`) that didn't exist.
* **Silent history loss:** `saveTurnToSession()` called `refreshSessions()`, which
  read the stale null `currentSessionId` and cleared `activeSession` — so the
  turn that was just saved was immediately forgotten.

## Verification

A JDK and the Kotlin compiler were fetched into `/tmp` (no dependencies added to
the repo) and the real sources were compiled:

```
=== 112 passed, 0 failed ===
```

That includes the new `AnswerDirectiveTest` and `AnswerPromptComposerTest`, plus
`MainViewModelTest` running the actual inference flow end to end.

Traced behaviour for the reported example:

```
USER:   What is the distance from Jalpaiguri to Delhi?
INTENT: FACT
PROMPT: Answer this request directly and completely:
        What is the distance from Jalpaiguri to Delhi?

        Answer the question directly in your first sentence, with the actual
        figure, fact, or result. Do not tell the user to look it up or search
        for it yourself. Finish the answer completely.

GATE:   "open Google Maps … to see the distance"  -> refusal detected, retried
GATE:   "about 1,470 km by road"                  -> accepted, shown
```

## Honest limit

This app runs entirely offline on a local Gemma 3 1B model. It will now *attempt*
and *give* an answer for things it knows or can estimate, and it will say so when
a request needs a live feed. It cannot fetch current weather or today's prices —
that is a data limitation, not a behaviour bug, and the prompt now handles it
gracefully instead of dumping the chore on you.
