# Settings Page Overrides

> **PROJECT:** PocketAgent  
> **Generated:** 2026-11-27 by `ui-ux-pro-max` (`offline privacy settings theme model management`)  
> **Scope:** Settings, privacy center, theme picker, and model manager only.

> ⚠️ Generated glassmorphism, lensing, and fluid morph transitions are rejected: they conflict with
> the offline-first Warm Editorial + Swiss direction. Z-order is explicit and shallow.

---

## Layout Overrides

- Full-width sections with `16dp` horizontal gutters and `UiTokens.SPACING_20_DP` between sections.
- Settings rows keep `UiTokens.TOUCH_TARGET_DP` minimum height; long labels wrap rather than clip.
- Theme mode, model import, privacy, and export are distinct sections with `sectionHeading` labels.

## Z-Order Scale

| Layer | Value | Usage |
| :--- | :--- | :--- |
| Content | `0` | Normal settings rows |
| Sticky header | `10` | Section heading while scrolling |
| Bottom sheet | `20` | Model manager and memory center |
| Dialog | `30` | Confirmation and action review |

No arbitrary `z-index` values beyond this scale.

## Motion & Theme

- Light and dark palettes are independently checked; system mode follows `ThemeMode.SYSTEM`.
- Reduced motion skips bottom-sheet slide/fade and renders the final state immediately.
- No translucent lensing, parallax, or morph transitions; flat surface contrast carries hierarchy.

## Privacy Copy

- Settings state plainly that models, sessions, notes, and files remain on-device.
- No telemetry, account, sync, or cloud-toggle controls are shown.

## Verification

- See `design-system/pocketagent/ACCESSIBILITY_CHECKLIST.md` for contrast and screenshot evidence.
