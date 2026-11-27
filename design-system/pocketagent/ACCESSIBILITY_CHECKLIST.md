# PocketAgent Accessibility Checklist

> Static checks are verified by `UiThemeTest.kt` and source inspection. Device/screenshot evidence is
> intentionally marked **Pending Task 10** until emulator runs and captured images exist.

---

## Verification Matrix

| Requirement | Static Status | Device / Screenshot Evidence | Notes |
| :--- | :--- | :--- | :--- |
| `375dp` compact layout | PASS (tokens) | Pending Task 10 | Single column, `16dp` gutters, wrapping labels. |
| `768dp` layout | PASS (tokens) | Pending Task 10 | Vault grid becomes two columns. |
| `1024dp` layout | PASS (tokens) | Pending Task 10 | Vault grid may reach three columns; chat bubbles remain readable. |
| `>= 48dp` touch targets | PASS | Pending Task 10 | `UiTokens.TOUCH_TARGET_DP` used by `pillButton`, `iconTouchButton`, `vectorIconButton`, `createActionPillButton`. |
| `>= 4.5:1` normal text contrast | PASS | Code verified | Ratios below; asserted in `UiThemeTest.kt`. |
| `>= 3:1` large text / active icon contrast | PASS | Pending Task 10 | Hero subtitle ratios are `>= 6.22:1`; status dots rely on adjacent text labels. |
| Visible keyboard focus | PARTIAL | Pending Task 10 | Controls are focusable; visible focus ring must be confirmed on device. |
| Reduced motion | PASS | Pending Task 10 | `sheetEntranceDurationMs(0f) == 0L`; final static state rendered. |
| Wrapping chips / labels | PASS (spec) | Pending Task 10 | Page overrides forbid unlabeled ellipsis truncation. |
| Accessible error summary | PARTIAL | Pending Task 10 | Chat override requires persistent inline summary; device announcement not yet proven. |
| No clipped text | PASS (spec) | Pending Task 10 | Rows and bubbles use `WRAP_CONTENT`; inspect at all three widths. |
| Dark mode independently checked | PASS (tokens) | Pending Task 10 | Distinct `DARK_PALETTE`; contrast ratios below. |
| Vector icons only | PASS | Pending Task 10 | `res/drawable/ic_*.xml`; no emoji glyphs as UI chrome. |

---

## Computed WCAG Ratios

| Pair | Ratio |
| :--- | ---: |
| Light `ink` on `bg` | 16.61:1 |
| Light `ink` on `surface` | 17.49:1 |
| Light `ink` on `surfaceElevated` | 15.50:1 |
| Light `muted` on `bg` | 5.47:1 |
| Light `muted` on `surface` | 5.76:1 |
| Light `muted` on `surfaceElevated` | 5.10:1 |
| Light `teal` on `mint` | 5.48:1 |
| Light `teal` on `surface` | 6.39:1 |
| Light success ink on success bg | 7.00:1 |
| Light danger ink on danger bg | 5.66:1 |
| Light warning ink on warning bg | 5.65:1 |
| Dark `ink` on `bg` | 16.23:1 |
| Dark `ink` on `surface` | 14.37:1 |
| Dark `ink` on `surfaceElevated` | 12.48:1 |
| Dark `muted` on `bg` | 8.40:1 |
| Dark `muted` on `surface` | 7.44:1 |
| Dark `muted` on `surfaceElevated` | 6.46:1 |
| Dark `teal` on `mint` | 6.17:1 |
| Dark `teal` on `surface` | 7.75:1 |
| Dark success ink on success bg | 10.33:1 |
| Dark danger ink on danger bg | 7.00:1 |
| Dark warning ink on warning bg | 10.28:1 |
| Light hero subtitle on gradient start / end | 11.57:1 / 7.24:1 |
| Dark hero subtitle on gradient start / end | 10.69:1 / 6.22:1 |

All normal-text pairs clear `4.5:1`; palette anchors were not changed.

---

## Evidence Links (fill in Task 10)

- Chat 375dp light: Pending Task 10
- Chat 375dp dark: Pending Task 10
- Vault 768dp: Pending Task 10
- Settings 1024dp: Pending Task 10
- Reduced-motion run: Pending Task 10
- Keyboard-focus run: Pending Task 10
