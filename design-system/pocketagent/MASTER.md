# PocketAgent Design System — MASTER

> **Aesthetic Direction:** Warm Editorial + Swiss Minimalism  
> **Architecture:** Pure Android Views / Drawables + Kotlin (`UiTheme.kt`, `UiComponents.kt`, `res/drawable/ic_*.xml`) — zero Compose, zero AppCompat, zero Material Components.

---

## 1. Core Principles

1. **Warm Editorial Clarity:** Light mode pairs warm archival paper (`#FAF9F6`) with crisp white surfaces (`#FFFFFF`) and deep espresso ink (`#1C1917`). Dark mode shifts to deep forest-obsidian (`#0E1412`) with elevated pine surfaces (`#17211E` / `#1F2D29`) and soft mint-ivory ink (`#E9F1EF`).
2. **Swiss Structural Grid:** Clean `1dp` hairline borders, generous breathing room, deliberate typographic hierarchy, and restrained use of a single signature accent (`teal` / `mint`).
3. **Zero Emoji Icons in UI Chrome:** All action buttons, status affordances, navigation controls, and message actions use crisp `24dp × 24dp` Android vector drawables (`ic_*.xml`). Never use emoji glyphs as UI icons.
4. **Accessible by Default:** Every interactive control meets `48dp × 48dp` minimum touch targets, `>= 4.5:1` WCAG AA text contrast (`>= 3:1` for large text and icons), and explicit `contentDescription` labels.

---

## 2. Color Tokens (`UiTheme.kt`)

All colors are resolved via `UiThemeResolver.paletteFor(mode, systemNightActive)` into an immutable `UiPalette`.

### 2.1 Surface, Text & Accent Tokens

| Token | Light Mode | Dark Mode | Semantic Role |
| :--- | :--- | :--- | :--- |
| `bg` | `#FAF9F6` | `#0E1412` | Window & root scaffold background |
| `surface` | `#FFFFFF` | `#17211E` | Primary card, dialog, sheet, input surface |
| `surfaceElevated` | `#F4F1EA` | `#1F2D29` | Elevated cards, icon button wells, subtle panels |
| `ink` | `#1C1917` | `#E9F1EF` | Primary body & heading text |
| `muted` | `#6B655E` | `#A0B2AD` | Secondary labels, hints, metadata, muted icons |
| `teal` | `#0F6B5E` | `#4EC4B2` | Primary brand accent, active states, positive links |
| `tealDark` | `#0B5046` | `#2E9A8A` | Pressed/deep accent emphasis |
| `mint` | `#E5F0EC` | `#1B3530` | Primary pill button fill, text selection highlight |
| `border` | `#E6E1D6` | `#2B3E39` | `1dp` hairline borders & dividers |
| `stopCrimson` | `#B91C1C` | `#EF5350` | Stop-generation action |
| `scrimOverlay` | `#8012110F` | `#B3050908` | Modal & drawer backdrop scrim |

### 2.2 Ripple & Interaction Tokens

| Token | Light Mode | Dark Mode | Usage |
| :--- | :--- | :--- | :--- |
| `rippleOnMint` | `#330F6B5E` | `#334EC4B2` | Ripple on mint-filled primary pills |
| `rippleOnSurface` | `#1A1C1917` | `#24E9F1EF` | Ripple on surface/outline buttons & icon buttons |
| `rippleOnAccent` | `#33FFFFFF` | `#33FFFFFF` | Ripple on solid teal/gradient surfaces |

### 2.3 Chat Bubble & Code Tokens

| Token | Light Mode | Dark Mode | Usage |
| :--- | :--- | :--- | :--- |
| `userBubbleBg` | `#EFECE4` | `#1A342F` | User message bubble fill |
| `userBubbleBorder` | `#D9D3C7` | `#2D5950` | User message bubble `1dp` stroke |
| `assistantBubbleBg` | `#FFFFFF` | `#17211E` | Assistant message bubble fill |
| `assistantBubbleBorder` | `#E6E1D6` | `#2B3E39` | Assistant message bubble `1dp` stroke |
| `inlineCodeBg` | `#EEECE6` | `#22332E` | Inline code span background |
| `inlineCodeInk` | `#0F4C43` | `#7BE0D0` | Inline code span text |
| `codeBg` | `#141716` | `#0B1110` | Fenced code block body (always dark editorial slate) |
| `codeHeaderBg` | `#1E2321` | `#131D1B` | Fenced code block language/copy header |
| `codeBorder` | `#2C3330` | `#263834` | Code block border |
| `codeText` | `#E9EFEA` | `#E4EFEA` | Code block plain text |
| `codeKeyword` | `#7DD3FC` | `#7DD3FC` | Syntax: keywords |
| `codeString` | `#86EFAC` | `#86EFAC` | Syntax: strings |
| `codeComment` | `#8C9692` | `#7D938D` | Syntax: comments |
| `codeNumber` | `#FDBA74` | `#FDBA74` | Syntax: numbers |

### 2.4 Semantic Status & Hero Tokens

| Token | Light Mode | Dark Mode | Usage |
| :--- | :--- | :--- | :--- |
| `successBg` / `Border` / `Ink` | `#E8F5E9` / `#C8E6C9` / `#1B5E20` | `#152E24` / `#2A5845` / `#86EFAC` | Success banners & confirmations |
| `dangerBg` / `Border` / `Ink` | `#FFEBEE` / `#FFCDD2` / `#B91C1C` | `#331B1B` / `#5E2B2B` / `#FF8A80` | Destructive actions & errors |
| `warningBg` / `Border` / `Ink` | `#FFF8E1` / `#FFE082` / `#8D5700` | `#312612` / `#5C4720` / `#FCD34D` | Warnings & caution notices |
| `statusLoadedGpu` | `#10B981` | `#34D399` | GPU runtime active indicator |
| `statusLoadedCpu` | `#0EA5E9` | `#38BDF8` | CPU runtime active indicator |
| `statusStandby` | `#F59E0B` | `#FBBF24` | Model standby indicator |
| `statusMissing` | `#DC2626` | `#F87171` | Missing model indicator |
| `heroGradientStart` / `End` | `#1F2927` / `#174F47` | `#12302C` / `#19584F` | Hero pill/header gradient |
| `heroSubtitleInk` | `#D9E5E2` | `#CDE5DF` | Subtitle text over hero gradient |

---

## 3. Typography Rules

- **Primary UI Font:** `Typeface.create("sans-serif", Typeface.NORMAL)` and `Typeface.BOLD` (system Roboto / sans-serif).
- **Code Font:** `Typeface.MONOSPACE` for fenced code blocks and inline code spans.
- **Casing:** Always `setAllCaps(false)` on buttons and tabs. Sentence case for labels and actions.
- **Type Scale:**
  - **Dialog / Sheet Title:** `17sp–18sp`, `BOLD`, `palette.ink`
  - **Body / Message Copy:** `15sp`, `NORMAL`, `palette.ink`
  - **Secondary / Helper Copy:** `13sp–14sp`, `NORMAL`, `palette.muted`
  - **Primary Pill Button:** `13sp` (`12sp` compact), `BOLD`
  - **Metadata / Badges / Captions:** `11sp–12sp`, `NORMAL` or `BOLD`, `palette.muted`

---

## 4. Shape, Spacing & Elevation

- **Base Grid:** `4dp` / `8dp` spatial rhythm (`12dp`, `14dp`, `16dp`, `20dp` container padding).
- **Corner Radii:**
  - Inputs & compact cards: `14dp`
  - Standard cards & message bubbles: `16dp`
  - Alert dialogs: `20dp`
  - Bottom sheets: `22dp` top-left & top-right, `0dp` bottom-left & bottom-right
  - Action pills & circular icon buttons: `24dp`–`30dp` (full pill)
- **Borders:** `maxOf(1, dp(context, 1))` (`1dp`) hairline stroke using `palette.border`.
- **Elevation:** Flat Swiss layering via surface contrast and `1dp` borders; elevated cards use subtle `2dp` elevation.

---

## 5. Accessibility & Contrast

- **Minimum Touch Target:** All clickable controls (`pillButton`, `secondaryOutlineButton`, `dangerButton`, `iconTouchButton`, `vectorIconButton`) enforce `minWidth >= 48dp` and `minHeight >= 48dp`.
- **Contrast Ratio:**
  - Normal text (`ink` on `bg`/`surface`, `teal` on `mint`, `dangerInk` on `dangerBg`, `successInk` on `successBg`) MUST maintain `>= 4.5:1` WCAG AA contrast.
  - Large text, borders, and active vector icons MUST maintain `>= 3.0:1` contrast.
- **Screen Readers:** Every `ImageButton` and symbol button sets `contentDescription` and `isFocusable = true`.

---

## 6. Iconography (`app/src/main/res/drawable/ic_*.xml`)

- **Canvas:** `24dp × 24dp`, `viewportWidth="24"`, `viewportHeight="24"`.
- **Stroke Style:** `android:strokeWidth="1.8"`, `android:strokeLineCap="round"`, `android:strokeLineJoin="round"`.
- **Tint-Ready:** Pure `#FFFFFFFF` stroke/fill so `tintedVectorDrawable(context, resId, tintColor)` can safely `.mutate().setTint(...)` for any palette token.
- **Standard Action Set:**
  - `ic_copy.xml` — Copy text/code
  - `ic_retry.xml` — Regenerate / retry response
  - `ic_more.xml` — Overflow / more actions
  - `ic_edit.xml` — Edit prompt / rename
  - `ic_chevron_right.xml` — Expand / navigate forward
  - `ic_chevron_down.xml` — Collapse / dropdown
  - `ic_sparkle.xml` — Deep Think mode
  - `ic_send.xml` — Send message
  - `ic_check.xml` — Confirmed / selected state

---

## 7. Motion & Haptics

- **Bottom Sheet Entrance:** `220ms` slide-up (`translationY` from `48dp` → `0dp`) + fade (`alpha` `0f` → `1f`) using `DecelerateInterpolator`.
- **Micro-Interactions:** `150ms–180ms` state transitions; system `RippleDrawable` feedback on all touchable surfaces.
- **Haptics:** `performSubtleHaptic(view, isConfirm)` triggers `KEYBOARD_TAP` on standard actions and `CONFIRM` (API 30+) on completions.
- **Safe Insets:** Bottom sheets apply `WindowInsets` (`navigationBars()` on API 30+, `systemWindowInsetBottom` on API 28–29) to bottom padding and display a `36dp × 4dp` centered drag handle pill.
