<div align="center">

[简体中文](README.md) · **English**

# Screen Translator

**Real-time on-screen translator for Android · capture → OCR → translate → overlay**

[![License](https://img.shields.io/github/license/ciddwd/overlay-translator?style=flat-square)](LICENSE)
[![Release](https://img.shields.io/github/v/release/ciddwd/overlay-translator?include_prereleases&style=flat-square)](../../releases)
[![Downloads](https://img.shields.io/github/downloads/ciddwd/overlay-translator/total?style=flat-square)](../../releases)
[![Stars](https://img.shields.io/github/stars/ciddwd/overlay-translator?style=flat-square)](../../stargazers)
[![Issues](https://img.shields.io/github/issues/ciddwd/overlay-translator?style=flat-square)](../../issues)
![Android API](https://img.shields.io/badge/Android-8.0%20%28API%2026%29%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)

Capture via MediaProjection / Shizuku → on-device or cloud OCR → LLM / MT → floating overlay.
No ROOT, fully self-contained, designed for visual novels, manga, game dialogue and any on-screen text.

[Install](#-install) · [Usage](#-usage) · [Configuration](#%EF%B8%8F-configuration) · [Contributing](#-contributing) · [Releases](../../releases) · [Issues](../../issues)

</div>

---

## ✨ Features

- **Capture**: MediaProjection + ImageReader (foreground service with `mediaProjection` type, Android 14+ compatible); optional Shizuku path to skip the per-session permission dialog
- **Trigger**: tap the floating button for one-shot, long-press opens an arc menu (rotated fly-out animation, 0.85 alpha); loop mode every 2 s by default, dHash diff skips static frames, **skips a round while the previous overlay is still on screen** to avoid flicker; an outer ring on the floating ball visualises the countdown; optional accessibility service to bind **Vol+ and Vol- held together for 300 ms** as a global trigger
- **Floating ball edge dock**: liquid "hugging" dock geometry (Dynamic Island-style); configurable inset from the physical screen edge (0–40 dp, dodges gesture zones, with live pink preview overlay); auto-dock after 3 s idle (optional)
- **Region selection**: full-screen rubber-band, remembers the last region, avoids OCR noise from the rest of the screen
- **OCR engines** (router, swap in settings; UI grouped **On-device / Cloud**):
  - On-device: ML Kit (Latin / Japanese / Chinese / Korean, AUTO switches by character set), PaddleOCR PP-OCRv5 mobile (ONNX Runtime)
  - Cloud: Baidu OCR (5 endpoints) / Tencent OCR (incl. RecognizeAgent LLM-augmented OCR with ParagNo paragraph grouping) / Youdao OCR (ocrapi, single-tap setup)
- **Source language ↔ OCR linkage**: when you change source language, the app checks whether the current OCR engine can recognize it; if not it recommends a better engine; if you're on cloud OCR with a generic language mode but a precise one is available, it offers an upgrade. The reverse also works: if you change the OCR side, it suggests adjusting source language to match — instead of undoing what you just did.
- **Translation engines** (router + **Test connection** button):
  - OpenAI-compatible chat completions (DeepSeek / SiliconFlow / Zhipu / Ollama / OpenAI …) with SSE streaming; Test fetches the model list to populate a picker
  - DeepL (free / Pro auto-detected; Test returns current month's used / total character quota) + **self-hosted deeplx support**: protocol selector (official / deeplx / auto fallback), independent Custom Token field that prevents the official key from being leaked to third parties, Bearer / DeepL-Auth-Key auto-pick
  - **Youdao PicTrans** (ocrtransapi, end-to-end: ships the full screenshot, returns translated regions in one call — **bypasses the OCR engine setting**; auto-rotates box coordinates by orientation)
  - **Google** (unofficial endpoint, no key required; proxy required inside mainland China)
- **Overlay**:
  - Two render modes: per OCR boundingBox (glued to source text) / single bottom banner
  - 5 built-in themes (Classic Dark / Amber Gold / Paper Light / Frost Glass / Custom) + font size, opacity, border, offset
  - Smart collision avoidance with neighbouring OCR boxes; wrap or compact single line (long translations in single-line mode auto-scroll via Marquee instead of "…" truncation)
  - **Merge adjacent OCR boxes** (essential for comics / subtitles): OCR engines often split one sentence into several adjacent boxes; merging them before translation eliminates overlay overlap
    - **Orientation detection** (per-box portrait ratio → H/V) + mirrored horizontal/vertical merge
    - Vertical Japanese: right-to-left column ordering
    - **Furigana (ふりがな) filtering**: narrow ruby-text columns adjacent to a wider kanji column are dropped to avoid duplicated translation like "shippai / 失败"
    - Three strengths (Conservative / Standard / Aggressive) for different layouts
  - LRU translation cache, hits skip token cost
- **Image preprocessing**: 2× upscale, color invert, Otsu binarize (for low-contrast / white-on-dark / colored noise)
- **The app itself**:
  - English / 简体中文 UI + Light / Dark / Follow-system theme, both apply instantly
  - In-settings search (matches both Chinese and English keywords)
  - All API Key / Secret fields use password masking with show/hide toggle
  - **Update check**: auto on app open (throttled 24 h) + manual button; calls GitHub Releases API directly, falls back to "Open release page" if the API is unreachable
  - **Crash recorder**: uncaught exceptions + native crashes / ANR / OOM kill (Android 11+) are stored with device info + a redacted settings snapshot + the stacktrace, viewable / exportable from the log screen after restart
  - Vendor ROM compatibility shortcuts (auto-start / battery whitelist for Xiaomi / OPPO / VIVO / Huawei / Samsung)
  - **Cleartext HTTP locked to private subnets**: only LAN / 127.0.0.1 / link-local can use `http://` by default; public hosts must use HTTPS. Extra hosts can be whitelisted in the "Network" section
  - **i18n fallback fix**: untranslated system languages (Uyghur, Hindi, etc.) now fall back to English instead of accidentally showing Chinese

## 📊 Comparison with similar apps

> Rough reference compiled from publicly available info in 2026; the other apps may have moved on. PRs welcome to correct.

Focused on the "game / manga on-screen translation" niche:

| Aspect | **Screen Translator (this)** | Gaminik (proprietary) | Aiyike (proprietary) | Google Translate | Immersive Translate |
|---|---|---|---|---|---|
| Free / no ads | ✅ | Subscription | In-app purchase | ✅ | ✅ |
| Open source | ✅ Apache 2.0 | ❌ | ❌ | ❌ | Partial |
| **Floating-ball overlay translation** | ✅ liquid dock | ✅ | ✅ | ❌ | ❌ (weak on mobile) |
| On-device OCR (no image upload) | ✅ ML Kit / PaddleOCR | ❌ (cloud-first) | ❌ (cloud-first) | ✅ | ❌ |
| Pluggable OCR engines | ✅ 8 options | Limited | Limited | ❌ (own only) | ❌ |
| Pluggable translation engines | ✅ 5+ incl. LLM | Limited | Limited | ❌ (own only) | ✅ |
| Self-hosted backend (deeplx etc.) + AUTO fallback | ✅ | ❌ | ❌ | ❌ | ❌ |
| LLM translation (GPT / DeepSeek / Ollama / self-host) + SSE streaming | ✅ | Partial | Partial | ❌ | ✅ |
| Shizuku capture (skip per-session permission dialog) | ✅ | ❌ | ❌ | ❌ | ❌ |
| Manga / subtitle OCR paragraph merge (incl. vertical, furigana filter) | ✅ 3 strengths | ❌ | ❌ | ❌ | ❌ |
| Strict isolation of official key vs. third-party token | ✅ separate Custom Token | — | — | — | — |
| Cleartext HTTP restricted to private subnets + whitelist | ✅ | — | — | — | — |

**Positioning**: in the "game / manga on-screen translation" niche, be the only one that is **open source + on-device-engine-capable + self-host-friendly**. The differentiation against incumbents lives in *privacy / self-hosting / engine choice*, not in OCR or translation quality itself (anyone can plug into the same cloud OCR and DeepL/LLM engines).

## 📸 Screenshots

**Live overlay** — Discord rules page, OCR boxes glued to the source text:

| Classic Dark | Paper Light |
|---|---|
| <img src="docs/screenshots/overlay-discord-dark.png" width="320" alt="Classic dark overlay" /> | <img src="docs/screenshots/overlay-discord-light.png" width="320" alt="Paper light overlay" /> |

**In-game** — Sandship UI, Chinese detected and overlaid:

<img src="docs/screenshots/overlay-game.png" width="640" alt="In-game overlay" />

**Comic / subtitle scene** — manga bubbles OCR'd by column, translation glued to source:

| Korean manga | Vertical Japanese manga |
|---|---|
| <img src="docs/screenshots/overlay-manga.png" width="320" alt="Korean manga overlay" /> | <img src="docs/screenshots/overlay-manga-jp.png" width="320" alt="Vertical Japanese manga overlay" /> |

**Settings**:

| App language / theme / translator | OCR engine / preprocessing | Overlay style preview |
|---|---|---|
| <img src="docs/screenshots/settings-top.png" width="240" alt="Settings top" /> | <img src="docs/screenshots/settings-ocr.png" width="240" alt="OCR engine settings" /> | <img src="docs/screenshots/settings-display.png" width="240" alt="Overlay display settings" /> |

## 📦 Install

1. Grab the latest `ScreenTranslator-x.y.z.apk` from [Releases](../../releases)
2. Tap it on your Android device (first time you'll need to allow "Install unknown apps" in system settings)
3. Grant **Overlay** and **Notification** permissions on first launch

Only `arm64-v8a` is published. Each APK ships with a `.sha256` so you can verify integrity against `Get-FileHash` / `sha256sum`.

## 🚀 Usage

1. Open the app, tap **Start capture service**, accept the system "Start recording?" dialog
2. Switch to any game / VN / manga app
3. Tap the floating button → translation appears in ~2-3 s
4. Long-press the floating button → toggle loop mode (every 2 s default; the outer progress ring sweeps once per capture; dHash skips still frames)
5. Tap the translation bar → hide

Optional:
- Enable the accessibility service so **holding Vol+ and Vol- together for 300 ms** acts as a global trigger (no screen-reading, no view-tree parsing)
- Install [Shizuku](https://github.com/RikkaApps/Shizuku) and grant permission; in settings, switch the capture path to Shizuku to skip the per-session system dialog

## ⚙️ Configuration

Open the app and tap **Settings**. The top of the settings page lets you switch **App language** and **Theme**; any section is reachable through the search icon, matching both Chinese and English keywords.

### OCR engines

| Engine | Good for | Notes |
|---|---|---|
| **On-device** ML Kit (auto / latin / ja / zh / ko) | Default; Japanese / Chinese / Korean / Latin | Offline, on-device |
| **On-device** PaddleOCR PP-OCRv5 mobile | Multilingual dense text, UI buttons | First use needs ONNX model download — see below |
| **Cloud** Baidu OCR | Fallback when ML Kit / Paddle miss | Needs API Key + Secret, pay-per-call; 5 endpoints (basic / with-position / accurate / accurate-with-position / web-image); image size / aspect-ratio limits |
| **Cloud** Tencent OCR | Same | Needs SecretId + SecretKey; 3 endpoints: GeneralBasic / GeneralAccurate / **RecognizeAgent** (LLM agent, integrated with ParagNo paragraph grouping) |
| **Cloud** Youdao OCR | Simple one-tap | Needs App ID (API Key) + App Secret; `langType` auto-derived from source language, no separate picker |

**PaddleOCR model download**: Settings → "Download PaddleOCR model" pulls the following three files from HuggingFace / hf-mirror into `<filesDir>/models/paddle/`:

- `det.onnx` (DBNet detector, ~4.5 MB)
- `rec.onnx` (CRNN recognizer, ~15.7 MB)
- `keys.txt` (v5 dictionary, ~90 KB)

You can override the mirror URL in settings, or import the files manually from local storage.

**Baidu OCR caveats**: image limits are *longest side ≤ 4096 px, shortest side ≥ 15 px, aspect ratio 1:4–4:1, base64 < 4 MB*. The first two are handled automatically (downscale + JPEG quality fallback); aspect ratio out of range can only be fixed by shrinking the capture region.

### Translation engines

**OpenAI-compatible**:

- **Base URL**: end with `/v1/`, e.g.:
  - SiliconFlow `https://api.siliconflow.cn/v1/`
  - OpenAI `https://api.openai.com/v1/`
  - Zhipu BigModel `https://open.bigmodel.cn/api/paas/v4/`
  - Self-hosted Ollama `http://<host>:11434/v1/`
- **API Key**: the `sk-xxx` from your provider
- **Model name** (examples):
  - SiliconFlow: `Qwen/Qwen2.5-7B-Instruct`, `Qwen/Qwen2.5-14B-Instruct`
  - OpenAI: `gpt-4o-mini`, `gpt-4o`
  - Zhipu: `glm-4-flash`, `glm-4-plus`
  - Ollama: `qwen2.5:7b`, `llama3.1:8b`
- **Prompt template**: defaults to a galgame conversational style and is editable. The default prompt follows the UI language: if you never edited it, switching UI language migrates it to the new locale's default; customized prompts are left untouched.

**DeepL**: paste the Auth Key (free tier keys end with `:fx`); the free / pro endpoint is picked automatically. **Test connection** also returns this month's used / total character quota.

**Youdao PicTrans** (end-to-end engine): paste one Youdao App ID + App Secret (shared with the OCR side). When selected, CaptureService **bypasses the OCR engine setting** and sends the whole screenshot to `ocrtransapi`, which returns translated regions directly. Box coordinates are auto-rotated by orientation to fix position offsets on rotated images. **Test connection** ships a 2×2 px tiny image and parses errorCode to distinguish key / service / rate-limit issues.

**Google** (unofficial endpoint): no key needed, free. **Proxy required inside mainland China**; Google may rate-limit or change the endpoint at any time — for learning only.

### Display

- **Render mode**: BLOCKS (per OCR box, glued to source) / BANNER (one bar at the bottom)
- **Placement** (BLOCKS only): below / overlap / above + pixel-level x/y offset
- **Theme**: 5 presets + custom (bg / fg / border ARGB)
- **Avoidance & merge**: collision detection clamps translation width so it doesn't bleed into neighbouring OCR boxes; OCR-side box merging offers **Conservative / Standard / Aggressive** strengths — Conservative suits VNs / dense passages, Standard fits most scenes, Aggressive suits comic bubbles split into many columns (may occasionally merge adjacent bubbles)

## ⚠️ Known limitations

- Android 14+ shows a system permission dialog on every fresh capture session — that's by design. The Shizuku path avoids it.
- Some ROMs (Xiaomi / OPPO / VIVO) kill background services or block overlays by default. You need to whitelist battery + allow background start manually. The app provides in-product shortcuts.
- Anti-cheat networked games may flag MediaProjection as a screen-recorder cheat — this project is for single-player / VN / manga only.
- Screens marked `FLAG_SECURE` (some banking / video apps) capture as black; this project does not bypass it.
- PaddleOCR on-device inference takes 1–3 s per shot on entry-level devices (Snapdragon 7-series or lower). Use region selection alongside.
- Chinese ROMs (HyperOS / MIUI, etc.) silently drop background-Service toasts; OCR / network failures, loop toggle, etc. now use floating overlays (red error bar auto-dismisses in 4.5 s, gray info bar in 1.8 s).
- Auto-start permission is a vendor-specific concept and **Android has no public API to query it** — the button is always tappable and just opens the ROM's own auto-start list for you to flip the switch manually. Battery whitelist uses the standard system API and shows "Already enabled" once granted.

## 🗺️ Roadmap

- **M0**: MediaProjection capture + ML Kit + PaddleOCR + OpenAI-compatible translation + floating button + bottom banner
- **M1**: region persistence, SSE streaming, per-boundingBox overlay rendering, ROM guidance, i18n (zh / en) + Light / Dark theme, in-settings search
- **M2**: Korean ML Kit, dual-volume-key global trigger, source-language ↔ OCR linkage, three-strength adjacent box merging, loop progress ring, crash recorder + log screen, GitHub Releases update check
- **M3 (current · 0.3.0)**: "Test connection" on all translator engines; Youdao OCR + Youdao PicTrans end-to-end engine; Google translation (unofficial); Tencent RecognizeAgent with ParagNo grouping; OCR merge orientation detection + furigana filtering; Shizuku release fix (R8 keep); loop mode skips when overlay is active; long single-line translations use Marquee scroll
- **M4**: inpainting-style overlay blending (color sampling + adaptive font size); on-device manga-ocr (kha-white, Japanese manga, reuses PaddleOCR DBNet); custom translation fonts (bundled + user-uploaded .ttf); Shizuku advanced path (UserService + aidl); chat history / TTS / glossaries

## 🤝 Contributing

Contributions of any kind — bug fixes, features, UI polish, translations, doc tweaks — are welcome.

### Branch & PR rules

> ⚠️ **Do not open PRs against `main`.**
>
> `main` is the stable trunk that drives releases; **only maintainer-reviewed merges land there**.
>
> External contributors:

1. **Open an issue first** to discuss direction — avoid wasted work / scope mismatches
2. **Fork** the repo, branch off the latest `main`:
   ```bash
   git checkout -b feat/your-idea origin/main
   ```
3. **Develop and test locally** (at least `./gradlew installDebug` on a real device)
4. **Open the PR against `dev`** (if `dev` doesn't exist yet, ping the maintainer in the issue to create it). The maintainer aggregates multiple PRs on `dev`, retests, then merges into `main`
5. **Direct push / force-push to `main` is forbidden** (enforced by branch protection)

### Translation contributions

To add a new language or fix an existing one:

1. Copy `app/src/main/res/values/strings.xml` to `app/src/main/res/values-<lang>/strings.xml` (e.g. `values-zh-rTW`, `values-ja`)
2. Translate the **values** inside `<string>` tags only. **Keep** the `name="..."` key and placeholders (`{source}`, `{target}`, `%1$s`, `\n`, etc.)
3. Add `<locale android:name="<lang>" />` to `app/src/main/res/xml/locales_config.xml`
4. Append a row to `APP_LANGUAGE_OPTIONS` in `app/src/main/java/com/gameocr/app/ui/SettingsScreen.kt`
5. In the PR description, attach coverage (X / Y strings translated) and screenshots of the localized settings page

We may migrate to [Weblate](https://weblate.org/) when there are 10+ languages.

### Commit messages

Use a simplified [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: new feature
fix:  bug fix
docs: documentation
refactor: refactor (no behaviour change)
chore: build / CI / tooling
i18n: translations
```

Write the subject in either English or Chinese — **don't mix in one line**. Keep the first line ≤ 72 chars.

### Code of conduct

- Be civil, stay on topic
- No off-topic content, ads, or politics
- Maintainers reserve the right to close issues / PRs that don't match the project direction, with a brief reason

## 🙏 Acknowledgements

### Upstream

- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) · PP-OCRv5 mobile model
- [bukuroo/PPOCRv5-ONNX](https://huggingface.co/bukuroo/PPOCRv5-ONNX) · ONNX-converted v5 mobile mirror
- [Shizuku](https://github.com/RikkaApps/Shizuku) · privileged channel without ROOT
- [ML Kit](https://developers.google.com/ml-kit) · Google's on-device OCR
- [ONNX Runtime](https://onnxruntime.ai/) · on-device inference engine
- [Jetpack Compose](https://developer.android.com/jetpack/compose) / [Material 3](https://m3.material.io/) · UI stack
- [Hilt](https://dagger.dev/hilt/) · dependency injection
- [Retrofit](https://square.github.io/retrofit/) + [OkHttp](https://square.github.io/okhttp/) · networking
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) · JSON
- [Timber](https://github.com/JakeWharton/timber) · logging

### Contributors

<a href="https://github.com/ciddwd/overlay-translator/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=ciddwd/overlay-translator" />
</a>

(Will appear automatically after the first PR is merged.)

### Inspiration

PC tools in the VNR / Visual Novel Reader family have served galgame / VN players for years. This project carries that pipeline to Android, redesigned for the phone (battery, ROM constraints, touch UI).

## 📄 License

Code is licensed under [Apache-2.0](LICENSE). Models and third-party dependencies retain their own licenses.

---

# 🛠️ Developer guide

Build / debug / release details for contributors. End users only need the APK from [Releases](../../releases) — skip this section.

## Tech stack

- Kotlin 2.x · Jetpack Compose · Hilt
- Android `minSdk 26` / `targetSdk 35` (runs on Android 8.0+, best on 10+)
- Retrofit + OkHttp + kotlinx.serialization
- DataStore (business settings) + SharedPreferences (theme / locale — anything needing sync read) + Room (cache)
- ONNX Runtime Android (PaddleOCR on-device)
- ML Kit on-device text recognition
- Shizuku API

## Project layout

```
app/src/main/java/com/gameocr/app/
  capture/    Screenshotter interface + MediaProjection / Shizuku impls + region selection + frame diff
  ocr/        OcrEngine interface + ML Kit / PaddleOCR / Baidu / Tencent / Youdao + RoutingOcrEngine (H/V orientation detection, furigana filter, paragraph clustering)
  translate/  Translator interface + OpenAI / DeepL / Youdao PicTrans (end-to-end) / Google (unofficial) + LRU cache + RoutingTranslator
  overlay/    Floating button + translation / loading / error overlays
  service/    CaptureService foreground service (capture → OCR → translate → render orchestrator)
  trigger/    Accessibility service (optional volume-key trigger)
  shizuku/    Shizuku permission + IBinder bridge
  rom/        Vendor ROM compatibility shortcuts (Xiaomi / OPPO / VIVO / Huawei / Samsung)
  data/       Settings model + DataStore repository + ThemeModePrefs + AppLocalePrefs
  di/         Hilt modules
  ui/         MainActivity + MainScreen + SettingsScreen + LogScreen + Compose theme

app/src/main/res/
  values/                Default (zh-CN) strings.xml + themes.xml + colors.xml
  values-en/             English strings.xml
  xml/locales_config.xml Per-app locales the app supports (Android 13+)

tools/local_ocr_debug/  Python scripts mirroring the Android PaddleOCR pipeline on PC (see "Development & debugging")
.github/workflows/      CI: push tag → auto release
```

## Build

### Prerequisites

- Android Studio Ladybug (2024.2) or newer; JDK 17 (bundled with Android Studio)
- Android SDK 35 (compileSdk) + Build-Tools 34+
- Device / emulator: Android 8.0 (API 26) or higher

### Clone & configure

```bash
git clone https://github.com/ciddwd/overlay-translator.git
cd overlay-translator
cp local.properties.example local.properties
# Edit local.properties and point sdk.dir at your local Android SDK
```

### Generate the Gradle wrapper jar

The repo intentionally does not commit `gradle/wrapper/gradle-wrapper.jar`. Two options to obtain it:

- **Option A (recommended)**: open the project in Android Studio; the IDE downloads the wrapper as part of sync
- **Option B**: if you already have Gradle 8.10+ installed, run `gradle wrapper --gradle-version 8.10.2` from the repo root

### Compile

```bash
./gradlew assembleDebug          # output: app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # install directly to a connected device
```

Only `arm64-v8a` is built (see `ndk.abiFilters` in `app/build.gradle.kts`; 32-bit and x86 are skipped to keep the APK small).

## Development & debugging

### PaddleOCR local tuning

`tools/local_ocr_debug/` ships Python scripts that reproduce the Android PaddleOCR pipeline 1:1 on PC for offline tuning and regression:

```bash
# 1. Pull the on-device ONNX models
adb exec-out "run-as com.gameocr.app.debug cat files/models/paddle/det.onnx" > tools/local_ocr_debug/models/det.onnx
adb exec-out "run-as com.gameocr.app.debug cat files/models/paddle/rec.onnx" > tools/local_ocr_debug/models/rec.onnx
adb exec-out "run-as com.gameocr.app.debug cat files/models/paddle/keys.txt" > tools/local_ocr_debug/models/keys.txt

# 2. Grab a test screenshot
adb exec-out screencap -p > sample.png

# 3. Run it through the same algorithm as Android
pip install onnxruntime pillow numpy opencv-python-headless pyclipper shapely
python tools/local_ocr_debug/run_v3_kotlin_equiv.py sample.png
```

`sample.png.v3.png` is a debug visualization with detected boxes overlaid; the console prints per-box position, average score and recognized text.

There's also `run_v2.py` (PaddleOCR upstream baseline; depends on cv2 + pyclipper) for evaluating changes against the official implementation.

## Release

Push a tag → CI builds → APK is uploaded to GitHub Releases. `.github/workflows/release.yml` triggers on `push tag v*`, runs `assembleRelease`, and attaches the signed APK + sha256 to the matching Release.

### One-time: signing keystore and GitHub Secrets

```bash
# Generate a release keystore locally (pick your own passwords / alias, keep them safe)
keytool -genkeypair -v \
  -keystore release.jks \
  -keyalg RSA -keysize 2048 -validity 36500 \
  -alias gameocr \
  -storepass <STORE_PASSWORD> -keypass <KEY_PASSWORD> \
  -dname "CN=GameOcr, OU=, O=, L=, ST=, C=CN"

# Encode to base64 for GitHub Secrets (macOS/Linux: base64 -w0 release.jks)
certutil -encode release.jks release.jks.b64   # Windows
# Copy the content between -----BEGIN/END----- (line breaks are fine)
```

Add 4 secrets under **Settings → Secrets and variables → Actions**:

| Secret | Content |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | the base64 string from above |
| `RELEASE_KEYSTORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | the value after `-alias`, e.g. `gameocr` |
| `RELEASE_KEY_PASSWORD` | key password (may equal the keystore password) |

> ⚠ **Never commit `release.jks`** — verify `*.jks` and `*.keystore` are in `.gitignore`.

### Release flow

```bash
# 1. Bump versionName / versionCode in app/build.gradle.kts on main
# 2. Push a tag
git tag v0.3.0
git push origin v0.3.0

# 3. Watch the Release workflow in the Actions tab
#    Once green, ScreenTranslator-0.2.0.apk and .sha256 land on the Releases page
```

Common CI failures:

- **Missing secrets**: the workflow's first step says `Secret RELEASE_KEYSTORE_BASE64 is not set`
- **Base64 decode error**: stray newlines / missing characters; run `base64 -d` locally to validate
- **Wrong signing password**: double-check what you passed to `-storepass` / `-keypass`; alias is case-sensitive

You can reproduce the same flow locally:

```bash
export RELEASE_KEYSTORE_PATH=/path/to/release.jks
export RELEASE_KEYSTORE_PASSWORD=<STORE_PASSWORD>
export RELEASE_KEY_ALIAS=gameocr
export RELEASE_KEY_PASSWORD=<KEY_PASSWORD>
./gradlew clean assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

When `RELEASE_KEYSTORE_PATH` is unset, `assembleRelease` succeeds but produces an unsigned APK that can't be installed directly. For day-to-day dev, `assembleDebug` is enough.
