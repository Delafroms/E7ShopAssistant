# E7SA — E7 Shop Assistant

> **Risk notice**: This tool automates Epic Seven through the Accessibility Service. It **may violate the game's
> Terms of Service and puts your account at risk**. You use it at your own responsibility. This project is not
> affiliated with Smilegate in any way.

E7SA is a helper for the **Secret Shop** in *Epic Seven*: screenshot → recognition → decision → tap.
It buys target items automatically and refreshes the shop when nothing is worth buying.

- Languages: [简体中文](docs/README.zh-CN.md) · [English](docs/README.en.md) · [日本語](docs/README.ja.md) · [한국어](docs/README.ko.md)

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
(see "Testing Status").

## Features (implemented)

- **Two recognition engines**: YOLO visual detection / traditional CV (row structure derived from the
  "Buy" button text + icon-area colour signature + text checks)
- **Two independent tap pipelines**: AI tapping (YOLO as eyes + a dedicated decision state machine) and
  traditional tapping (text anchors). Neither falls back to the other.
- **Budget gates**: gold cap, Skystone cap, bookmark/medal holding caps; stop when reached
- **Idle power handling**: keeps the screen on while running; can lock the screen when the task finishes
- **Floating task panel**: status, stage, counters, event log, start/pause/stop (draggable)
- **Records**: session history, cumulative purchases and spending
- **Diagnostics**: raw frame capture and a recognition regression bench (long-press the version label)
- **Three themes** (Steam dark card / Steam OLED black / Blue Archive light) and full Chinese/English UI
- **Optional cloud sync**: WebDAV with your own server and credentials — no configuration, no network traffic

*Experimental (usable, still being validated)*: sleep mode (full-frame recognition, slower but steadier),
custom background image and top title image, theme transition easing selection.

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

## Known Issues

Full register: [docs/TEST_REPORT.md](docs/TEST_REPORT.md) (RT-001 ~ RT-014).

- **RT-004 / RT-005**: native crash and model-load detection are fixed but **not verified on a device**.
- **RT-009**: after an app update the system clears the accessibility enablement; re-enable it manually.
- **RT-010**: duplicated layout code between the two themes (technical debt, no functional impact).
- **RT-012**: recognition accuracy depends on the model and screen conditions (extreme resolutions, UI
  scaling, occlusion, mid-animation frames may be missed).
- **RT-013**: automation **may violate the game's Terms of Service** — account risk.
- **RT-014**: requires the Accessibility Service; the one-tap channel needs elevated permissions.

## Testing Status

- **Done**: 115/115 unit tests; decision state-machine tests for both pipelines; 5 adversarial (red-team)
  cases; Android Lint (baseline + fail on new issues); on-device install/launch/UI/accessibility smoke;
  WebDAV upload and download against a real cloud service.
- **Not done**: overnight continuous runs, multiple devices/resolutions, permission revocation and service
  reclamation, real network failures, long-run memory and battery, per-screen manual review of every language.
- **Verdict**: **partially tested**. Simulation-level results are trustworthy; long-run on-device behaviour
  is unknown. **Not recommended for unattended use.**

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

Copyright © 2026 heyyo
