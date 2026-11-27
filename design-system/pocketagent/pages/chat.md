# Chat Page Overrides

> **PROJECT:** PocketAgent  
> **Generated:** 2026-11-27 by `ui-ux-pro-max` (`offline on-device AI assistant privacy focused chat`)  
> **Scope:** Chat screen only. All unlisted rules come from `design-system/pocketagent/MASTER.md`.

> ⚠️ PocketAgent keeps Warm Editorial + Swiss Minimalism as the source of truth. Web-only palettes,
> fonts, and glass effects from the generated recommendation are intentionally not adopted.

---

## Layout Overrides

- Single scrolling conversation column with `16dp` horizontal gutters; no artificial `px` max-width.
- User and assistant bubbles keep their `UiPalette` surface tokens and `1dp` hairline borders.
- Composer stays bottom-anchored and respects IME insets; send/stop controls keep `48dp` touch targets.
- At `768dp` and `1024dp`, the transcript column may widen but bubbles remain left-aligned for readability.

## Streaming & Status

- Stream generated text token by token; never show a bare spinner for 10s or longer.
- Expose one polite, atomic status label for generation state; do not create competing live regions.
- Label assistant output as on-device generated content without presenting it as human.
- Errors are announced in a persistent inline error summary, not a transient toast alone.

## Actions

- Regenerate, copy, retry, refine, and stop actions use `ic_*.xml` vector drawables only.
- Every icon-only control supplies `contentDescription`, `isFocusable = true`, and a visible focus state.
- Thumbs up/down or Regenerate affordances are reachable by keyboard and screen reader.

## Motion

- Thinking/recording pulses and typing indicators are decorative; disable or freeze them when
  `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` via `sheetEntranceDurationMs`.
- Reduced motion renders the final static state instead of sliding or fading content.

## Verification

- Contrast ratios and device evidence live in `design-system/pocketagent/ACCESSIBILITY_CHECKLIST.md`.
