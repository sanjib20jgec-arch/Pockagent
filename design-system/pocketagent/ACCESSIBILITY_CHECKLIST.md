# PocketAgent Accessibility Checklist

> Static checks are verified by `UiThemeTest.kt` and source inspection. Device/screenshot evidence
> comes from [CI run 45925286889](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45925286889)
> (commit `ea0eb1b`): six Roborazzi PNGs at 375dp and a green `connectedDebugAndroidTest` on API 28
> and API 35. See [docs/EVIDENCE.md](../../docs/EVIDENCE.md) for counts and blocked tests.

---

## Verification Matrix

| Requirement | Static Status | Device / Screenshot Evidence | Notes |
| :--- | :--- | :--- | :--- |
| `375dp` compact layout | PASS (tokens) | [chat](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/screenshots/chat_light_375.png), [vault](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/screenshots/vault_light_375.png), [settings](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/screenshots/settings_light_375.png) | Single column, `16dp` gutters, wrapping labels. |
| `768dp` layout | PASS (tokens) | Static only — no 768dp baseline captured | Vault grid becomes two columns; verified by tokens/page overrides, not a screenshot. |
| `1024dp` layout | PASS (tokens) | Static only — no 1024dp baseline captured | Vault grid may reach three columns; chat bubbles remain readable. |
| `>= 48dp` touch targets | PASS | Device smoke `layoutTouchTargets` green on API 28/35 | `UiTokens.TOUCH_TARGET_DP` used by `pillButton`, `iconTouchButton`, `vectorIconButton`, `createActionPillButton`; device check asserts the `Ask` control height. |
| `>= 4.5:1` normal text contrast | PASS | Code verified | Ratios below; asserted in `UiThemeTest.kt`. |
| `>= 3:1` large text / active icon contrast | PASS | Code verified | Hero subtitle ratios are `>= 6.22:1`; status dots rely on adjacent text labels. |
| Visible keyboard focus | PARTIAL | Not proven on device | Controls are focusable; visible focus ring cannot be captured by Roborazzi. |
| Reduced motion | PASS | Code verified | `sheetEntranceDurationMs(0f) == 0L`; final static state rendered. |
| Wrapping chips / labels | PASS (spec) | 375dp baselines above | Page overrides forbid unlabeled ellipsis truncation. |
| Accessible error summary | PARTIAL | Not proven on device | Chat override requires persistent inline summary; device announcement not yet proven. |
| No clipped text | PASS (spec) | 375dp baselines above | Rows and bubbles use `WRAP_CONTENT`; wider widths remain static-only. |
| Dark mode independently checked | PASS (tokens) | [chat](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/screenshots/chat_dark_375.png), [vault](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/screenshots/vault_dark_375.png), [settings](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/screenshots/settings_dark_375.png) | Distinct `DARK_PALETTE`; contrast ratios below. |
| Vector icons only | PASS | Source inspection | `res/drawable/ic_*.xml`; no emoji glyphs as UI chrome. |

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

## Evidence links

- Chat 375dp light: [chat_light_375.png](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/screenshots/chat_light_375.png)
- Chat 375dp dark: [chat_dark_375.png](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/screenshots/chat_dark_375.png)
- Vault 375dp light: [vault_light_375.png](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/screenshots/vault_light_375.png)
- Vault 375dp dark: [vault_dark_375.png](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/screenshots/vault_dark_375.png)
- Settings 375dp light: [settings_light_375.png](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/screenshots/settings_light_375.png)
- Settings 375dp dark: [settings_dark_375.png](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/screenshots/settings_dark_375.png)
- Screenshot report artifact: `PocketAgent-screenshot-reports` on [run 45925286889](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45925286889)
- Device smoke reports: `PocketAgent-device-reports-api-28`, `PocketAgent-device-reports-api-35` on the same run
- Reduced motion: asserted in `UiThemeTest.kt`; no animated capture
- Keyboard focus / TalkBack announcement: **Not yet proven on device** — no accessibility-scanner evidence
