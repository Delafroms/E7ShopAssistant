# E7SA — E7 Shop Assistant

[![Issues](https://img.shields.io/badge/Issues-welcome-blue?logo=github)](../../issues) [![Releases](https://img.shields.io/badge/Releases-latest-green?logo=github)](../../releases)

> **Risk notice**: This tool automates Epic Seven through the Accessibility Service. It **may violate the game's
> Terms of Service and puts your account at risk**. You use it at your own responsibility. This project is not
> affiliated with Smilegate in any way.

E7SA is a helper for the **Secret Shop** in *Epic Seven*: screenshot → recognition → decision → tap.
It buys target items automatically and refreshes the shop when nothing is worth buying.

- Languages: [简体中文](README.zh-CN.md) · [English](README.en.md) · [日本語](README.ja.md) · [한국어](README.ko.md)

> **Full documentation**: this English README covers the essentials. The Chinese
> **[README.zh-CN.md](README.zh-CN.md)** is the project's **main document** and the most complete version —
> where the two differ, the Chinese document is authoritative.

## Overview

The Secret Shop shows only 6 slots per refresh, and target items are rare (about 0.66% per slot for
Covenant Bookmarks, about 0.17% for Mystic Medals). Manual refreshing is expensive, so this project
automates the "look → judge → tap" loop with exactly three goals:

1. **Zero missed purchases** — if a buyable target is seen, buy it;
2. **Zero wrong purchases** — with insufficient evidence, do nothing rather than tap wrongly;
3. **Controllable budget** — hard gates on gold and Skystones (paid currency).

**Stage**: v1.0.0 is the first public release of a **personal project**, not a commercial product.
**Test completeness**: unit and adversarial tests pass at the simulation level; on-device verification
covers installation, launch, UI and cloud sync only. **Long unattended runs have not been verified**
(see "Testing status: verified / partially verified / not verified" below).

## Features (implemented)

- **Two recognition engines**: YOLO visual detection / traditional CV (row structure derived from the
  "Buy" button text + icon-area colour signature + text checks)
- **Two independent tap pipelines**: AI tapping (YOLO as eyes + a dedicated decision state machine) and
  traditional tapping (text anchors). Neither falls back to the other.
- **Budget gates**: gold cap, Skystone cap, bookmark/medal holding caps; stop when reached
- **Idle power handling**: keeps the screen on while running; can lock the screen when the task finishes
- **Floating task panel**: status, stage, counters, event log, start/pause/stop (draggable)
- **Records**: session history, cumulative purchases and spending
- **Gear scoring**: estimates gear value by stat weights (speed x2.0, crit rate x1.5, crit damage x1.1, percentage x1, flat atk/def x0.3, flat HP x0.15)
- **Diagnostics**: raw frame capture and a recognition regression bench (long-press the version label)
- **Four themes** (Steam dark card / Steam OLED black / Blue Archive light / DeepSeek) and full Chinese/English UI
- **Optional cloud sync**: WebDAV with your own server and credentials — no configuration, no network traffic

*Experimental (usable, still being validated)*: sleep mode (full-frame recognition, slower but steadier),
custom background image and top title image, theme transition easing selection.

## Fixes and version history

> The complete round-by-round fix log (root cause, function names, thresholds, verification status) is in
> **[docs/FIXES_SUMMARY.md](docs/FIXES_SUMMARY.md)**. This section lists only what directly affects users.

### v1.0.0 (versionCode 16)

**Fixes that involve real money** (the most important class):

| Problem | Consequence | Status |
|---|---|---|
| Accounting was gated on "verification succeeded" | The action had already happened, but a slow or failed verification meant it was never booked → the Skystone gate never fired and **paid currency was consumed without limit** | Fixed (ACTION → COMMIT), verified at the simulation level |
| A tap that did not take effect was still marked "handled" | Whole-screen missed purchases (AI pipeline) | Fixed, verified at the simulation level |
| Deterministic failures on the same row were retried forever | The same button was hammered over and over (63 / 182 taps measured) | Fixed (after 3 attempts the row is abandoned **temporarily**) |
| Row tolerance was derived from the icon-box height × 2 (≈300 px > 260 px row pitch) | Adjacent rows contaminated each other → tapping the wrong row livelocked | Fixed (tolerance is now derived from the row pitch, always < half the pitch) |

**Stability and safety**:

- Native use-after-free (opening the gear-score page while running crashed the process) — fixed, **not reproduced and verified on a device**
- Model-load failure could not be detected (0 recognition results while the model was reported healthy) — fixed, **not verified**
- The Shizuku one-tap grant channel was dead (R8 removed the provider) — fixed, verified on a device
- The device-wide accessibility safety switch was rewritten silently — fixed (now an explicit setting), verified on a device
- WebDAV sync always failed (HTTP 409, the remote directory was never created) — fixed, verified on a device against a real cloud service
- Accessibility event subscription narrowed from device-wide to the game package `com.stove.epic7.google`. **If your region uses a different package name, the event subscription will be empty - but screenshots and taps are unaffected** (this service never uses accessibility events, only the screenshot/gesture capability), so functionality is equivalent

**Other**: main-thread disk I/O, gear-score engine index out of bounds (Turkish 'İ' silently swallowing
characters), floating window destroyed without releasing its references, the service still shown as
"enabled" after unbinding, `didScroll` allocating 17 MB per frame, `handledRows` not synchronised
across threads.

## Architecture

```
Screenshot (Accessibility Service)
  → Preprocessing (scaling/cropping, resolution-independent sampling windows)
  → Scene detection (shop list / buy dialog / refresh dialog / network error / other)
  → Candidate detection (YOLO icon boxes ∪ OCR item-name lines)
  → Feature extraction (row structure from the "Buy" button pitch, icon colour signature)
  → OCR (PP-OCRv5)
  → Multi-signal fusion (text + icon corroboration + unique price match)
  → Filtering (sold out, already-handled rows, item-type switches)
  → Decision (BotEngine traditional / AiBotEngine AI)
  → Final click gate (ClickGate: valid coordinates, session not stopped) → gesture
```

- **Layered**: recognition answers "what is visible", decision decides "what to do", the device layer only
  takes screenshots and performs gestures.
- **The AI pipeline never falls back** to traditional tapping; when unsure it reports and stays still.
- **ACTION → COMMIT**: any state-changing action (purchase, refresh, budget deduction) is accounted for at
  the moment the action happens; verification only affects later decisions.
- Native C++ handles ncnn inference; the model is self-describing via `assets/e7sa_yolo.ncnn.meta`.

## Install and usage

**Requirements**: Android 11 (API 30)+; arm64-v8a or armeabi-v7a.
Verified on OnePlus PLR110 / Android 16 / ColorOS only; other devices and vendor ROMs are untested.

1. Install the APK from this repository's Releases page.
2. Open the app and enable the **Accessibility Service** (required for screenshots and gestures).
3. Optional: grant `WRITE_SECURE_SETTINGS` via adb to use the one-tap accessibility channel:
   `adb shell pm grant com.e7.shop android.permission.WRITE_SECURE_SETTINGS`
4. Accept the risk dialog, open the Secret Shop in Epic Seven, and press **Start**.
5. Set a **gold cap** and a **Skystone budget** first (0 = unlimited).

**Stop**: press Stop in the floating panel or the app. Pressing Start again always ends the previous
session, resets counters and resumes from the current screen.

**Recovery**: if nothing happens for a long time, check that the accessibility service is still enabled
(the system may turn it off after an app update). The app self-heals common cases (ineffective tap →
back-off retry, leftover dialog → send BACK).

**Reporting issues**: see "Bug reports" below.

## Security and privacy

| Item | Notes |
|---|---|
| Permission usage | Accessibility: screenshots and gestures. Overlay: task panel. Notifications: keep-alive notice. `WRITE_SECURE_SETTINGS` (optional): one-tap accessibility. Shizuku (optional): alternative grant channel |
| Screenshot / recognition data | Processed **on-device only**; never uploaded |
| Network | **Only when you configure WebDAV sync**. No configuration → no network requests. No telemetry, no analytics SDK |
| Stored data | App settings (SharedPreferences), run logs and statistics in app-private storage. WebDAV account and app password are stored separately and **excluded from system backup and device transfer** |
| Logs | Run logs may contain recognised on-screen text (in-game text). Review them before sharing |
| Data removal | Uninstalling removes all local data; statistics and logs can be cleared in settings |
| Test-version risk | This is the **first public release**; long-run behaviour is unverified. Privacy behaviour may change between versions — check the release notes |

## Security verification and red-team testing

### Goal

Not "prove that it works", but "**prove that it cannot be broken**".

### Scope and method

- **Scope**: recognition-layer deception (sold-out rows, fake Buy-button positions, tapping the wrong row,
  price ambiguity) + timing deception (a confirmation tap being swallowed, a dialog that never closes)
- **Method**: adversarial cases in `RedTeamFsmTest` — an Attacker composes the attacked screen while the
  system under test is the decision state machine itself. They are kept separate from the "works as
  designed" tests and deliberately construct scenes that can fool the engine.

### Results

| Attack scenario | Result |
|---|---|
| Sold-out row | **Defence holds** — not a single tap |
| Fake Buy button (the "Buy" text placed to the left of the icon) | **Defence holds** — no tap (the Buy key must be to the right of the candidate) |
| Wrong row (the row holds a bookmark, the dialog holds a medal) | **Defence holds** — the row is abandoned and cancelled immediately, without confirming |
| Price ambiguity / dialog content does not match | **Defence holds** — no purchase confirmation; after 3 consecutive attempts the row is abandoned temporarily |
| Dialog never closes (taps swallowed by the system) | **Defence holds** — the timeout counts conservatively, but **never writes a fake success record** (only UNCONFIRMED) |

### Red-team findings (fixed)

**RT-001 | Deterministic failures retried without limit**: three classes of **deterministic** failure on the
same row (`dialog verify FAIL`, Buy key not found, confirm key not found) used to share an exit path with
"the tap did not take effect", and the latter never counts a cap because "retry rather than miss a purchase"
→ the same button was measured being tapped **63 / 182 times**. Fixed: a separate budget was added; after 3
attempts the row is abandoned **temporarily** (a refresh or a scroll clears it — not a permanent skip).

**Boundary (important)**: "the tap did not take effect (the dialog never appeared, the button is still
purchasable)" is **not** capped — that path still never gives up on the row, because it is a recoverable
failure and giving up would mean a missed purchase.

### What this verification is **not**

- It is not a complete security audit: the scope covers only the attack surface above; no penetration test,
  code audit or formal verification was performed;
- "No problems found" does not mean "absolutely safe": the red-team cases cover **known** failure modes;
- The red-team cases verify the **decision state machine** (simulation level); **on-device behaviour was not
  reproduced case by case**.

## Known issues and limitations

Full defect register (RT-001 ~ RT-014): [docs/TEST_REPORT.md](docs/TEST_REPORT.md).

| ID | Issue | Status |
|---|---|---|
| RT-004 | Native use-after-free (opening the gear-score page while running → process crash; the only process-level crash defect) | Fixed, **not reproduced and verified on a device** |
| RT-005 | Model-load failure cannot be detected | Fixed, **not verified** |
| RT-009 | After an update the system clears the accessibility enablement | **Platform behaviour**, not fixed; see "Upgrade notes" |
| RT-010 | Layout code is still duplicated between the two themes (technical debt, no functional impact) | Partially fixed |
| RT-011 | No continuous integration | CI is provided, not yet green in the real repository |
| RT-012 | Recognition accuracy depends on the model and screen conditions (extreme resolutions, UI scaling, occlusion, mid-animation frames may be missed) | **Known limitation**, not fixed |
| RT-013 | Automation **may violate the game's Terms of Service** — account risk | **Known risk**, confirmation required on first launch |
| RT-014 | Requires the Accessibility Service; the one-tap grant channel needs elevated permissions | Design trade-off |

**Other known limitations**:

- `slot6Check` and `revealSlot6` differ by about 2.7× in swipe geometry span (0.72h vs 0.27h); unifying
  them requires an on-device regression first;
- The row-tolerance clamp floor is 10% of the screen height (about 127 px on a 1272-tall screen); below that
  pitch there is still a cross-row risk (the E7 shop pitch is a fixed ~20%, so it does not trigger);
- 84 orphan strings and a missing Chinese translation for `appearance_m3`, planned for V1.1.

## Testing status: verified / partially verified / not verified

### Verified

| Item | Evidence |
|---|---|
| Unit tests | **115 / 115 pass, 0 skipped** |
| Full state-machine tests | Both tap pipelines have FSM constraints (traditional: the money path "really bought → booked immediately → gate fires", missed-purchase regression, fail-closed; AI: must error out when the model is not loaded, never taps blind with zero perception, exits on stop) |
| Red-team cases | All 5 adversarial cases green (including positive evidence for the capping behaviour) |
| Lint gate | Android Lint baseline freezes existing issues + fails on new ones |
| On-device smoke | OnePlus PLR110 / Android 16: install, launch, UI rendering, accessibility binding, no crash |
| Cloud-sync end to end | WebDAV connect / upload / download (real cloud service) |
| Recognition regression bench | 89 real screenshots, both engines comparable against a baseline |

### Partially verified

- **RT-001 / RT-002 / RT-003** (infinite retry, missed purchase, budget gating): **simulation level only**,
  not verified on a device;
- **RT-010** (duplicated theme code): only the logic layer and the shared dialogs were extracted; the layout
  layer is untouched;
- **Recognition accuracy**: good on the regression-bench samples, but the samples come from a single device
  and a limited set of scenes.

### Not verified

The following have **explicitly never been done** — judge the risk accordingly:

1. **Overnight continuous unattended runs** — the only way to prove "zero missed purchases / zero wrong
   purchases", and it has not been completed;
2. **RT-004 / RT-005** (native crash, model-load detection): not reproduced and verified on a device after
   the fix;
3. **Multiple devices / resolutions / DPIs / Android 11–15 / tablets / foldables**;
4. **Permission revocation, the system reclaiming the service, app restarts, real network outages and weak
   networks**;
5. **Memory and battery over long runs**;
6. **Per-screen manual review of every language**;
7. **The CI workflow**: GitHub Actions is provided, but it has not yet run successfully in the real repository.

### Verdict

**Partially tested.** Unit and simulation-level conclusions are trustworthy; long-run on-device behaviour is
unknown. **Not recommended for unattended use.**

## Upgrade notes

1. **Signing change (important)**: v1.0.0 uses the production release key. Upgrading from an earlier
   **debug-signed** build **requires uninstalling first** (this clears local settings and cloud-sync
   credentials; upload your statistics over WebDAV first and download them again after reinstalling).
   Builds with the same signature can be upgraded in place.
2. **Accessibility will drop**: after an app update (including `adb install -r`) or `am force-stop`, the
   system's accessibility enablement **is cleared** (platform behaviour, RT-009). If the app reports that the
   accessibility service is not enabled, simply enable it once again.
3. **Losing the signing key means you can never ship an update to already-installed users** — the inherent
   cost of using a custom key, recorded here on purpose.

## Bug reports and security

- **Regular bugs**: use the [Bug Report template](.github/ISSUE_TEMPLATE/bug_report.yml) and include version,
  device/Android version, reproduction steps, expected and actual behaviour.
- **Do not post publicly**: accounts, passwords, device serial numbers, personal paths, or logs/screenshots
  containing personal data.
- **Security issues** (permission bypass, data leakage, arbitrary file access, third-party abuse):
  **do not open a public issue** — follow [SECURITY.md](.github/SECURITY.md) and report privately.

## Roadmap

- **In Progress**: validate the GitHub Actions workflow in the real repository; add the Gradle Wrapper.
- **Planned**: de-duplicate theme layouts; reproduce native crash paths on-device; end-to-end recognition
  automation; broader device coverage.
- **Experimental**: sleep mode, custom background/title image, transition easing selection.

## Documentation

| File | Content |
|---|---|
| [README.zh-CN.md](README.zh-CN.md) | Chinese main document — the most complete version of this README |
| [docs/FIXES_SUMMARY.md](docs/FIXES_SUMMARY.md) | Round-by-round fix log (root cause, fix, thresholds, verification status) |
| [docs/TEST_REPORT.md](docs/TEST_REPORT.md) | Defect register RT-001 ~ RT-014 and test records |
| [docs/REDTEAM_20260919.md](docs/REDTEAM_20260919.md) | Raw record of the red-team adversarial tests |
| [docs/V1-配置能力矩阵.md](docs/V1-配置能力矩阵.md) | Configuration capability matrix (audit contract and regression baseline) |
| [CHANGELOG.md](CHANGELOG.md) | Full version history |
| [.github/SECURITY.md](.github/SECURITY.md) | Private security reporting |
| [LICENSE](LICENSE) | AGPL-3.0 full text |

## Changelog

- Latest release and downloads: [GitHub Releases](../../releases)
- Full history: [CHANGELOG.md](CHANGELOG.md)

## License

Released under the **GNU Affero General Public License v3.0 (AGPL-3.0)** — full text in [LICENSE](LICENSE).

- You **may** use, modify, redistribute and **also use it commercially**;
- **Condition**: any derivative work (including network services) must also be licensed under AGPL-3.0 and
  **provide the complete source code to its users**. A rebranded, closed-source paid version is therefore not
  allowed; charging for an open-source distribution is.
- Third-party components keep their own licenses — see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- **The project name, icon and diagrams in the documentation are not covered by the code license**;
  Epic Seven names and assets belong to Smilegate.
- The bundled detection model was trained with the Ultralytics toolchain (AGPL-3.0); publishing the whole
  project under AGPL-3.0 satisfies its free-use condition.

Copyright (C) 2026 Delafroms
