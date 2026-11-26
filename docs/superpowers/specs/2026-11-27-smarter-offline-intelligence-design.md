# PocketAgent v1.4 Design: Smarter Offline Intelligence Pack (Approach C)

**Date:** 2026-11-27  
**Status:** Proposed for Approval  
**Target Version:** `1.4.0`

## 1. Intent & Why
On-device 1B–3B `.litertlm` models running at ~4.2 tok/s on mobile hardware currently underperform cloud agents in three areas:
1. **Logic & Coding:** Single-pass sampling with default `0.8` temperature causes arithmetic/code slips and weak constraint adherence.
2. **Bengali & Banglish Clarity:** Small models drift into awkward phrasing, and `NotesRetriever` (`Regex("[a-z0-9]+")`) silently drops all Bengali script words during local retrieval.
3. **Document & Context Grounding:** Overlapping excerpts and noisy context waste limited `2K/4K/8K` token budgets.

**Why Approach C (Hybrid):** Everyday chats keep **zero extra decode latency** via smart single-pass routing and bilingual retrieval, while an optional **1-tap `Deep Think` toggle** unlocks two-pass self-critique when accuracy matters more than speed.

## 2. Goals, Non-Goals & Anti-Goals
- **Goals:**
  - Auto-tune reasoning bias & sampling temperature per task (`Code/Math`, `Docs/Facts`, `Bengali/Banglish`).
  - Fix Unicode Bengali tokenization across `NotesRetriever` and `OfflineKeywordIndex` so Bengali notes and Document Vault queries retrieve accurately.
  - Add an opt-in `Deep Think` toggle (persisted) that runs a bounded self-critique verification pass.
  - Deduplicate overlapping Document Vault / Attachment excerpts to preserve context budget.
- **Non-Goals:**
  - Any cloud API calls (`android.permission.INTERNET` remains strictly forbidden).
  - Running multiple parallel LLMs (single serial `inferenceExecutor` only).
- **Anti-Goals:**
  - Forcing 2-pass latency on simple greetings/facts by default.
  - Bloating `SYSTEM_INSTRUCTION` beyond `ContextBudgetManager.TokenBudget.DEFAULT_SYSTEM_TOKENS` (384).

## 3. Approaches Considered
- **Approach A (Single-Pass + Context Hygiene):** Fastest (`0` extra latency), but no self-correction for hard coding/logic prompts.
- **Approach B (Always-On Two-Pass Critique):** Strongest per-turn checking, but doubles wait time (`~2x`) on every prompt at 4.2 tok/s.
- **Approach C (Chosen — Hybrid):** Zero-latency `ReasoningRouter` + Unicode Bengali RAG + optional `Deep Think` two-pass toggle.

## 4. Architecture & Components

### 4.1 `ReasoningRouter.kt` (New Pure-Kotlin Module + `ReasoningRouterTest.kt`)
- **Inputs:** User task text, attachment/vault presence, user's configured base `temperature`.
- **Classification:**
  - `TaskProfile`: `QUICK`, `LOGIC_CODE`, `GROUNDED_DOCS`, `CREATIVE`.
  - `LanguageMode`: `ENGLISH`, `BENGALI_SCRIPT`, `BANGLISH` (detects Unicode `\u0980-\u09FF` and common romanized Banglish markers).
- **Outputs (`RoutingDecision`):**
  - `effectiveTemperature`: e.g. `0.25f` for `LOGIC_CODE`, `0.35f` for `GROUNDED_DOCS`, user's base temperature otherwise.
  - `compactDirective`: `<= 60` tokens of task- and language-specific guidance injected into the system instruction.
  - `shouldAllowDeepThink`: `false` for trivial `QUICK` greetings even if `Deep Think` is toggled ON (avoids wasting 2 passes on `"hi"`).

### 4.2 Bilingual Unicode Retrieval (`AgentActivityTracker.kt` & `OfflineKeywordIndex.kt`)
- Replace ASCII-only `Regex("[a-z0-9]+")` with Unicode letter/digit tokenization (`Regex("[\\p{L}\\p{N}]+")`) in `NotesRetriever` (and check `OfflineKeywordIndex` for identical ASCII restriction).
- Add unit tests verifying Bengali-script notes and Document Vault search return exact ranked matches.

### 4.3 Optional `Deep Think` Mode (`MainActivity.kt` & `ComposerChromeHelper.kt`)
- Add `Deep Think: OFF / ON` chip to the Quick-Mode Bar (persisted via `PREF_DEEP_THINK`, default `false`).
- In `MainActivity.suggestSteps()`:
  - Pass 1 generates the initial answer (`runGuardedGeneration`).
  - If `isDeepThinkEnabled && routing.shouldAllowDeepThink` and not cancelled/stopped, Pass 2 runs a concise **Self-Critique & Verify** prompt (`"Review your draft for math, code syntax, and missed constraints, then output only the corrected final answer."`) while updating the live stage badge (`"Deep Think · Verifying"`).

### 4.4 Context Deduplication (`MainActivity.kt` / `ContextBudgetManager.kt`)
- Deduplicate identical or near-identical vault/attachment excerpts before building the bounded attachment block.

## 5. Verification Plan
- Unit tests: `ReasoningRouterTest.kt`, `AgentActivityTrackerTest.kt` (Bengali notes), `OfflineKeywordIndexTest.kt` (Bengali docs), `ComposerChromeHelperTest.kt` (Deep Think chip).
- Packaging check: `python scripts/package_source.py --check`.
- CI: GitHub Actions `gradle testDebugUnitTest assembleDebug` green with zero errors.
