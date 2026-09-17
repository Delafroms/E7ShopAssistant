# E7 Shop Assistant (Native Android App)

Epic Seven secret-shop auto farming app: automatically buys covenant bookmarks
(184,000 gold) and mystic medals (280,000 gold), skips everything else,
refreshes with 3 skystones when nothing valuable is shown.

Native Kotlin app. No AutoX, no root, no ADB. Uses only the system
accessibility service for human-like gestures and screenshots.

## Risk warning

Automation may violate Epic Seven ToS and risk a ban. The bot uses human-like
behavior (random delays, tap offsets, curved swipes, random rests) to lower
detection chance, but no tool can guarantee safety. Use at your own risk.

## Features

- Bilingual UI (Chinese / English) with instant language switch.
- **OCR text recognition (primary)**: the shop screen is read with on-device
  ML Kit Chinese OCR — the bot finds "誓约书签 / 神秘奖牌" by their text and
  taps the row directly. This is the same architecture as the Rem assistant
  (which ships OCR engines, not icon templates) and works at any resolution.
- Icon template matching (fallback): corrected bookmark/medal crops matched
  with **OpenCV** (TM_CCOEFF_NORMED, brightness-invariant, SIMD-fast — the
  same library the Rem assistant ships) + color-signature verification;
  a pure-Kotlin matcher remains as a last-resort fallback.
- Buy-confirmation verification: the green "Buy" dialog must be visible
  before the confirm button is pressed.
- Swipe verification: the swipe to reveal slot 6 is confirmed by comparing
  screenshots; if the list did not scroll, the gesture is retried slower
  before falling back to a refresh.
- Holding caps: stop the run when bookmarks / mystic medals reach your
  target counts (0 = unlimited).
- Gold spend cap for refreshes, gold floor, skystone budget.
- **Equipment scoring**: pick a gear-detail screenshot from the gallery and
  the app OCRs the stat lines (on-device ML Kit Chinese recognition) and
  scores them with the standard E7 weights (speed x2, crit rate x1.5,
  crit dmg x1.1, % stats x1, flat stats x0.3/0.15). No root needed.
- **New floating window**: draggable E7-themed status ball (breathing pulse,
  status letter) that opens an animated control card with live stats
  (bookmarks/medals/refreshes/runtime) and Start/Pause/Stop buttons.
- Appearance: custom background image for the page area only (top logo bar
  and bottom navigation unchanged).
- Records with Jianguoyun WebDAV cloud sync.
- Risk warning gate.
- Human-like behavior: random delays, tap offsets, curved swipes, random rests.

## How to build and run

1. Open this folder in Android Studio (Quail 3 or newer).
2. Let Gradle sync finish (first time downloads dependencies).
3. Plug in the phone (USB debugging on), press Run, select your device.
4. On the phone:
   - Settings -> Accessibility -> enable "E7 Shop Assistant Service".
5. In the app: confirm the risk warning. Icon templates are built in, no
   capture needed (optional override via the Capture buttons).
6. Start, then switch to the game's secret shop screen.

## Shop mechanism implemented

- 6 item slots, 5 visible; swipe down once reveals slot 6.
- Loop: check screen -> buy if bookmark/medal found -> swipe down -> check
  slot 6 -> if nothing, swipe back up and press "Refresh" (3 skystones).
- Confirm dialog: the green "Buy" button on the right is pressed.

## Project layout

```
app/src/main/java/com/e7/shop/
  MainActivity.kt               UI: logo, pages, bottom nav
  ShopAccessibilityService.kt   gestures + screenshots + farming loop
  TemplateCaptureActivity.kt    drag-box template capture
  bot/Humanizer.kt              random delays/offsets/rests
  bot/TemplateMatcher.kt        sliding-window template matching
  data/AppConfig.kt             settings store
  data/RecordStore.kt           totals + sessions (JSON)
  net/WebDavSync.kt             Jianguoyun WebDAV upload/download
  score/ScoreEngine.kt          gear stat parsing + scoring rules
  score/EquipmentScoreActivity.kt  gallery screenshot scoring (ML Kit OCR)
```

## First-run calibration

Default tap coordinates are estimated from 2800x1272 screenshots. If taps land
in the wrong place, adjust the ratio values in `AppConfig` defaults
(`refreshX/Y`, `buyX/Y`, `listLeft/...`, `swipeCenterX/...`) and rebuild.
