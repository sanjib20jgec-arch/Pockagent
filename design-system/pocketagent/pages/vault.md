# Vault Page Overrides

> **PROJECT:** PocketAgent  
> **Generated:** 2026-11-27 by `ui-ux-pro-max` (`offline local document vault private files`)  
> **Scope:** Document, artifact, note, and model vault screens only. All other rules come from `MASTER.md`.

> ⚠️ The generated Swiss recommendation is adopted for structure and restraint, not for its web
> palette or external fonts. PocketAgent keeps its Warm Editorial anchors exactly.

---

## Layout Overrides

- `375dp`: one column of vault cards with `16dp` gutters.
- `768dp`: two columns; `1024dp`: up to three columns. Card gutters stay at `16dp`.
- Cards use `palette.surface`, `palette.border`, and `UiTokens.CARD_RADIUS_DP`; no heavy shadows.
- Filenames, sizes, and timestamps wrap instead of truncating into unlabeled ellipses.

## Privacy & Status

- Each vault entry states that content stays on-device; no cloud, account, or sync affordances.
- Model, PDF, note, and artifact status badges use the semantic status tokens from `MASTER.md`.
- Destructive removal actions use `palette.dangerInk` on `palette.dangerBg` and require confirmation.

## Interaction

- Open, rename, pin, export, and delete controls use tinted `ic_*.xml` drawables, never emoji.
- Icon-only controls keep `48dp × 48dp` touch targets and explicit `contentDescription`.
- Bulk or multi-select states announce the selected count in one atomic status label.

## Verification

- Contrast and responsive evidence live in `design-system/pocketagent/ACCESSIBILITY_CHECKLIST.md`.
