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

### 🎯 In one line

Game / manga / visual novel on screen → tap the floating ball → translation appears overlaid on the source text in a couple of seconds.

### 🖱️ How to trigger

- **Tap the floating ball**: defaults to translating the whole screen; switchable to **Word-pick mode** — one tap lets you draw a rectangle around just the word or phrase you want translated (more precise)
- **Long-press the floating ball**: opens an arc menu with common actions one finger can reach: loop / re-pick region / switch between **"Translate full screen"** and **"Translate a word"** / quick source-target language switch / preset switch / settings / back to main. **Order and page size are configurable** in settings; extra buttons paginate with a "Next page" button automatically
- **Loop mode**: auto-translate every 2 s; static frames are skipped automatically — read a novel without lifting your finger
- **Volume two-key**: hold **Vol+ and Vol−** together for 0.3 s to trigger; your hands stay on the game (requires enabling the bundled accessibility service)
- **From any other app**: long-press to select some text in any app → tap "Screen Translator" in the system menu → translation card pops up immediately, no need to switch back to this app first

### 🔍 OCR (read the text on screen)

- **On-device**: ML Kit (CJK + Latin) / PaddleOCR / manga OCR — your screenshots never leave the phone; works offline; PaddleOCR supports multiple v5/v6 tiers
- **Local HTTP OCR**: connect to Umi-OCR / LunaTranslator services on your LAN or PC
- **Cloud**: Baidu / Tencent / Youdao — fall back to these when on-device misreads
- When you switch source language, the app checks whether the current OCR engine can read it; if not, it suggests a better one
- Optional orientation detection can route horizontal / vertical / rotated text to a better OCR path, or you can lock the direction manually

### 🌐 Translation engines

- **LLMs**: DeepSeek / ChatGPT / Zhipu / self-hosted compatible APIs… streamed token by token, no waiting for the whole reply
- **On-device LLMs**: Sakura (Japanese → Simplified Chinese ACGN/VN) and Hy-MT2 (multilingual translation) run from GGUF models after download, so they can translate offline once ready
- **DeepL**: free / Pro plan auto-detected, **supports self-hosted [deeplx](https://github.com/OwO-Network/DeepLX)** (open-source proxy, run on your own server, no key needed); pick between Official / deeplx / Auto-fallback
- **Youdao Pic-Trans**: skips the OCR step entirely — send the screenshot, get back boxes with translations. Great for comics.
- **Google**: no key, free (proxy required inside mainland China)
- Every engine has a **Test connection** button; DeepL additionally reports remaining monthly free quota

### 🔤 Word lookup (one tap = mini dictionary)

Not just full-screen translation. Draw a rectangle around a single word or phrase and a card pops up with:

- **Translation** — every engine returns this
- **Pronunciation / part of speech / multiple definitions / example sentence pairs** — only when an **LLM engine** is selected (Baidu / Tencent / DeepL etc. only return a plain translation)
- A "Copy source" and "Copy translation" button on the card; long-press any text inside to copy too
- Great for game / manga / VN vocabulary you don't recognize — one second per word, faster than switching to a dictionary app

### 🎨 How the overlay looks

- **Two render modes**: glued to each source box (BLOCKS), or packed into a **draggable / resizable floating window** — perfect for games with on-screen joysticks and buttons; you can lock the window to prevent accidental touches
- **5 color themes** + font size, opacity, border style (solid / dashed / dotted / double / groove) — preview updates live
- **Comic / subtitle optimizations**: sentences split across multiple OCR boxes get merged before translating; vertical Japanese is read right-to-left; tiny ruby-text columns (furigana) next to kanji are filtered out so you don't get duplicate translations; vertical scenes can use vertical translation layout
- **Marquee for long lines**: in single-line mode, long translations scroll horizontally instead of being truncated with "…"
- **Custom translation font**: import `.ttf` files for translated text only; the app UI keeps using the system font, and imported fonts stay available as selectable chips

### 🛠️ Small conveniences

- **English / 简体中文 UI + Light / Dark / Follow-system theme**, applies instantly
- **In-settings search**: can't find an option? Search in either language at the top of the settings page
- **System presets**: built-in bundles such as "offline Japanese manga OCR → Simplified Chinese"; missing models are listed before a preset can be applied
- **Translation cache**: identical lines don't burn through your API quota twice
- **Auto-saved crash reports**: if the app crashes or freezes, the next launch lets you view and export a sanitized report in one tap
- **Encrypted sensitive settings**: API keys, prompts, mirror URLs and presets are migrated into encrypted local storage to reduce plaintext leftovers
- **Update prompt**: checks for new versions when you open the home screen (at most once a day); offers a direct link if GitHub is unreachable
- **Chinese-ROM background guides**: shortcuts to auto-start / battery whitelist settings for Xiaomi / OPPO / VIVO / Huawei / Samsung devices that aggressively kill background services
- **Privacy default**: cleartext HTTP is only allowed inside your LAN by default; public hosts must use HTTPS

## 📊 Comparison with similar apps

> Rough reference compiled from publicly available info in 2026; the other apps may have moved on. PRs welcome to correct.

| Aspect | **Screen Translator (this)** | Gaminik / Aiyike (same codebase, proprietary) | Google Translate | Immersive Translate |
|---|---|---|---|---|
| Free / no ads | ✅ | Subscription or IAP | ✅ | ✅ |
| Open source | ✅ Apache 2.0 | ❌ | ❌ | Partial |
| Floating-ball overlay translation | ✅ | ✅ | ❌ | ❌ (weak on mobile) |
| On-device OCR (no image upload) | ✅ ML Kit + PaddleOCR | ❌ (cloud-first) | ✅ | ❌ |
| Pluggable OCR engines | ✅ On-device / local HTTP / cloud paths | Limited | ❌ | ❌ |
| Pluggable translation engines | ✅ Cloud LLM / on-device LLM / DeepL / DeepLX / Youdao / Google / Volcengine / Baidu / Tencent | 1–2 fixed | ❌ | Translation only |
| Self-hosted backend (deeplx / on-device GGUF / custom Bearer) | ✅ | ❌ | ❌ | ❌ |
| Floating-window mode (lockable, gesture-proof) | ✅ | ❌ | ❌ | ❌ |
| Comic vertical / orientation detection / furigana / subtitle paragraph merge | ✅ | ❌ | ❌ | ❌ |
| Word-lookup card (phonetics / POS / definitions / examples) | ✅ full dictionary on LLM engines | ❓ | ⚠️ inside Google Translate app only | ⚠️ weak on mobile |
| Translate selected text from any app | ✅ via system text-selection menu | ❓ | ✅ "Tap to Translate" | ❌ |
| Reorderable & paginated floating-ball arc menu | ✅ | ❌ | ❌ | ❌ |

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

| Language / theme / system presets | Translation backend |
|---|---|
| <img src="docs/screenshots/settings-top.png" width="280" alt="Settings top and system presets" /> | <img src="docs/screenshots/settings-translator.png" width="280" alt="Translation backend settings" /> |
| **OCR engines / orientation detection** | **Overlay style and font** |
| <img src="docs/screenshots/settings-ocr.png" width="280" alt="OCR engine and orientation model settings" /> | <img src="docs/screenshots/settings-display.png" width="280" alt="Overlay display and font settings" /> |
| **Arc-menu buttons** | **Floating-window mode options** |
| <img src="docs/screenshots/settings-arc-menu.png" width="280" alt="Arc-menu button settings" /> | <img src="docs/screenshots/settings-display-floating.png" width="280" alt="Floating-window mode settings" /> |
| **Box merge / floating ball** | |
| <img src="docs/screenshots/settings-floating.png" width="280" alt="Box merge & floating ball settings" /> | |

**Floating-window mode** — collects all source / translated lines into one draggable, resizable overlay so translations don't cover game controls (joysticks, action buttons, dialog-advance keys). The window can be **locked**: unlocked shows a close button + bottom resize handle and accepts drag / pinch; locked strips them away, leaving only the text and ignoring every gesture — no more accidental drags during gameplay.

| Unlocked (drag / resize / close) | Locked (gesture-proof, content only) |
|---|---|
| <img src="docs/screenshots/floating-window-unlocked.jpg" width="420" alt="Floating window unlocked" /> | <img src="docs/screenshots/floating-window-locked.jpg" width="420" alt="Floating window locked" /> |

**Arc menu (long-press) & word-lookup card** — Left: the arc menu that fans out when you long-press the ball: loop / re-pick region / switch between "Translate full screen" and "Translate a word" / back to main app (the button order is freely reorderable in settings). Right: the card you get after circling a single word on screen — pronunciation, part of speech, multiple definitions and example sentences (the full dictionary requires an LLM-based engine).

| Arc menu on long-press | Word-lookup card (with mini dictionary) |
|---|---|
| <img src="docs/screenshots/arc-menu.png" width="320" alt="Arc menu fanning out from the floating ball" /> | <img src="docs/screenshots/word-select.png" width="320" alt="Word-lookup card with phonetics, POS, definitions and examples" /> |

## 📦 Install

1. Grab the latest `ScreenTranslator-x.y.z.apk` from [Releases](../../releases)
2. Tap it on your Android device (first time you'll need to allow "Install unknown apps" in system settings)
3. Grant **Overlay** and **Notification** permissions on first launch

Only **`arm64-v8a` (64-bit ARM)** is published. armeabi-v7a / x86 are **not supported for now** — on-device OCR engines (PaddleOCR / ML Kit) ship large native libs, and 32-bit ARM lacks 64-bit NEON optimizations and has fewer registers, making inference noticeably slower and OCR wait times much longer. If you have a 32-bit device that needs this, please open an issue.

Each APK ships with a `.sha256` so you can verify integrity against `Get-FileHash` / `sha256sum`.

## 🚀 Usage

1. Open the app, tap **Start capture service**, accept the system "Start recording?" dialog
2. Switch to any game / VN / manga app
3. Tap the floating button → translation appears in ~2-3 s
4. Long-press the floating button → open the arc menu to toggle loop mode, re-pick the capture region, switch source/target language, switch presets, or toggle between full-screen and word-pick translation (loop defaults to every 2 s; dHash skips still frames)
5. Tap the translation bar → hide

Optional:
- Enable the accessibility service so **holding Vol+ and Vol- together for 300 ms** acts as a global trigger (no screen-reading, no view-tree parsing)
- Install [Shizuku](https://github.com/RikkaApps/Shizuku) and grant permission; in settings, switch the capture path to Shizuku to skip the per-session system dialog
- Pick a system preset at the top of settings, such as "Offline Japanese manga OCR → Simplified Chinese". If a required model is missing, the preset card lists it and offers a download action.
- For single-word lookup, switch the floating-ball action to **Word-pick mode** from the arc menu, then draw around the word / phrase. You can also select text in another app and invoke Screen Translator from the system selection menu.

## ⚙️ Configuration

Open the app and tap **Settings**. The top of the settings page lets you switch **App language** and **Theme**; any section is reachable through the search icon, matching both Chinese and English keywords.

### OCR engines

| Engine | Good for | Notes |
|---|---|---|
| **On-device** ML Kit (auto / latin / ja / zh / ko) | Default; Japanese / Chinese / Korean / Latin | Offline, on-device |
| **On-device** PaddleOCR PP-OCRv5 / v6 | Multilingual dense text, UI buttons, horizontal Chinese / English | Supports v5 mobile and v6 tiny / small / medium; models must be ready — see below |
| **On-device** manga OCR | Japanese manga, vertical bubbles, hand-drawn fonts | Uses manga-ocr ONNX and reuses DBNet detection; ~140 MB, downloadable or importable |
| **Local HTTP** Umi-OCR | You already run [Umi-OCR](https://github.com/hiroi-sora/Umi-OCR) on a PC / LAN server | Point the app at `http://<host>:<port>/api/ocr`; screenshots stay on your LAN |
| **Local HTTP** LunaTranslator | You already run [LunaTranslator](https://github.com/HIllya51/LunaTranslator) OCR on a PC / LAN server | Fill in the LunaTranslator OCR HTTP endpoint |
| **Cloud** Baidu OCR | Fallback when ML Kit / Paddle miss | Needs API Key + Secret, pay-per-call; 5 endpoints (basic / with-position / accurate / accurate-with-position / web-image); image size / aspect-ratio limits |
| **Cloud** Tencent OCR | Same | Needs SecretId + SecretKey; 3 endpoints: GeneralBasic / GeneralAccurate / **RecognizeAgent** (LLM agent, integrated with ParagNo paragraph grouping) |
| **Cloud** Youdao OCR | Simple one-tap | Needs App ID (API Key) + App Secret; `langType` auto-derived from source language, no separate picker |

**PaddleOCR models**: Settings → "Download PaddleOCR model" installs the selected model tier under `<filesDir>/models/paddle/<version>/`. PaddleOCR models do not ship inside the APK, so first use requires a download or local import; the default tier is v6 small, and you can switch between v5 mobile / v6 tiny / small / medium with HuggingFace, hf-mirror, or a custom mirror.

- `det.onnx` (DBNet detector, size varies from several MB to tens of MB by tier)
- `rec.onnx` (CRNN recognizer, usually tens of MB depending on tier)
- `keys.txt` / `keys.yml` (dictionary, ~90-150 KB)

**Orientation models**: the ONNX orientation package classifies full-image 0/90/180/270 rotation and text-line 0/180 direction. Once installed, the app can route vertical screenshots before OCR and rerun upside-down horizontal text while mapping boxes back to the original image. The models are bundled, but can still be redownloaded or imported.

**Manga OCR models**: Settings → "Download manga OCR model" installs the encoder / decoder / vocab / config files required by `l0wgear/manga-ocr-2025-onnx`. The public source is mainly Hugging Face; when that is unreachable, use a proxy, custom mirror, or local import.

**Automatic orientation detection**: enabled by default. Automatic mode combines image orientation, OCR result shape, and language to pick a better OCR path; if it misclassifies a frame, lock the direction manually to horizontal, vertical right-to-left, or stacked vertical.

**DBNet advanced thresholds**: the OCR page exposes detection probability, box score, unclip ratio, manga-OCR unclip, and bubble-cluster gap. Most users should leave these alone; tune them only for missed tiny manga text, split vertical columns, or noisy boxes, then reset to defaults if needed.

**Baidu OCR caveats**: image limits are *longest side ≤ 4096 px, shortest side ≥ 15 px, aspect ratio 1:4–4:1, base64 < 4 MB*. The first two are handled automatically (downscale + JPEG quality fallback); aspect ratio out of range can only be fixed by shrinking the capture region.

### Translation engines

**On-device LLM**:

- **Sakura (Japanese → Simplified Chinese)**: tuned for ACGN / VN / galgame translation, using the `Sakura-1.5B Qwen2.5 (Q5KS)` GGUF model (~1.26 GB). If the source is not Japanese or the target is not Simplified Chinese, it falls back to the OpenAI-compatible backend instead of misusing the model.
- **Hy-MT2**: Tencent's Hy-MT2-1.8B multilingual translation model, wired through the Q4_K_M GGUF (~1.13 GB). Source and target languages follow your settings.
- **Model management**: supports Hugging Face, hf-mirror, custom mirrors, and local GGUF import. Downloads resume after interruption. Large downloads on cellular or unknown networks show a traffic warning first.
- **Device requirements**: on-device LLMs are only enabled on supported 64-bit devices / OS versions. Unsupported devices or missing models are gated before runtime.

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

**Volcengine Translate** (ByteDance's translation service): enable "Machine Translation" in the Volcengine console, then paste the two keys it generates (AK / SK). Leave the region as default. Holds up well under loop mode without getting throttled.

**Baidu Translate** (heads-up: **not the same account as the "Baidu" OCR above** — sign up separately at the Baidu Translate Open Platform): paste APPID + secret key. The free personal tier is roughly 50k characters per month, enough for a few visual-novel sessions a day.

**Tencent Translate**: shares one key pair with the Tencent OCR above. If you already filled SecretId / SecretKey in the OCR section, nothing else to do here.

### Display

- **Render mode**: BLOCKS (per OCR box, glued to source) / **Floating window** (a draggable, resizable overlay — see the [screenshots section](#-screenshots))
- **Placement** (BLOCKS only): below / overlap / above + pixel-level x/y offset
- **Floating window**: switch between "source + translation" and "translation only" content modes; **lock** the window to disable dragging and resizing during gameplay; a "Reset position / size" button restores defaults in one tap
- **Theme**: 5 presets + custom (bg / fg / border ARGB + border style: solid / dashed / dotted / double / groove)
- **Font**: import `.ttf` files for the translation layer and preview only; imported fonts are kept as horizontally selectable chips, and reselecting an existing font does not reorder them
- **Avoidance & merge**: collision detection clamps translation width so it doesn't bleed into neighbouring OCR boxes; OCR-side box merging offers **Conservative / Standard / Aggressive** strengths — Conservative suits VNs / dense passages, Standard fits most scenes, Aggressive suits comic bubbles split into many columns (may occasionally merge adjacent bubbles)

### Presets and Arc Menu

- **System presets**: built-in bundles for "offline Japanese manga OCR → Simplified Chinese". A preset includes OCR, translator, language pair, display style, and tuning thresholds. Missing required models are shown item by item before the preset can be applied.
- **Custom presets**: save the current settings as a preset. A preset only shows as applied when all key settings still match; changing any key field returns the card to the unsaved state.
- **Arc-menu buttons**: drag to reorder and choose 2-6 visible buttons per page. If there are more actions, the last slot becomes "Next page". Actions can include loop, capture region, word/full-screen mode switch, language quick switch, preset quick switch, settings, and back to main app.

## ⚠️ Known limitations

- Android 14+ shows a system permission dialog on every fresh capture session — that's by design. The Shizuku path avoids it.
- Some ROMs (Xiaomi / OPPO / VIVO) kill background services or block overlays by default. You need to whitelist battery + allow background start manually. The app provides in-product shortcuts.
- Anti-cheat networked games may flag MediaProjection as a screen-recorder cheat — this project is for single-player / VN / manga only.
- Screens marked `FLAG_SECURE` (some banking / video apps) capture as black; this project does not bypass it.
- PaddleOCR on-device inference takes 1–3 s per shot on entry-level devices (Snapdragon 7-series or lower). Use region selection alongside.
- On-device LLM models are roughly 1 GB or larger, so first download should use Wi-Fi; low-memory devices may fail to load them reliably.
- The public manga-OCR source is mainly Hugging Face. If it is unreachable from your network, use a proxy, custom mirror, or local import.

## 🗺️ Roadmap

### ✅ Done

- [x] **Make it usable**: capture → read text → translate → show on screen, the whole chain working
- [x] **Easy to live with**: remember capture region / show translation as it streams / glue to source text / Chinese-ROM background guides / EN+CN UI + light/dark themes / in-settings search
- [x] **Smarter and steadier**: Korean OCR, volume two-key global trigger, smart OCR recommendation when source language changes, three paragraph-merge strengths, loop progress ring, auto-saved crash reports, update prompts
- [x] **Engine expansion** (0.3.x): "Test connection" on every translator, added Youdao OCR + PicTrans + Google translate, Tencent agent paragraph grouping, furigana filter on manga, marquee scrolling for long lines
- [x] **From whole screen to single word** (0.3.4+): word-lookup card with mini dictionary (phonetics / POS / definitions / examples), one-tap toggle between "Translate full screen" and "Translate a word" on the floating ball, reorderable arc-menu, "Translate with Screen Translator" entry in the system text-selection menu
- [x] **Offline and vertical-text expansion** (0.3.5+): Sakura / Hy-MT2 on-device LLMs, manga OCR, PaddleOCR v6 and orientation models, Umi-OCR / LunaTranslator local HTTP OCR, system presets, custom translation fonts, arc-menu language / preset quick switches, DBNet advanced thresholds, encrypted sensitive settings

### 📋 Planned

- [ ] **Translation blends into the artwork** — smart color picking + adaptive font size, replacing the solid-color rectangle so it looks "painted in" instead of stuck on top
- [ ] **Shizuku capture fast enough for loop mode** — the current Shizuku path already skips the per-session permission dialog but tops out at ~5 FPS (one-shot only); upgrade it to a stable channel that holds up in loop mode
- [ ] **Translation history** — scroll back to previous screens you've translated
- [ ] **Read-aloud (TTS)** — listen to translations while you play
- [ ] **Glossary / term lock** — pin a fixed translation for names and items so they stay consistent across screens
- [ ] **Offline dictionary fallback** — give definitions even on plain-MT engines like Baidu / Tencent / DeepL that don't return dictionary data on their own

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

### Build locally

```bash
git clone https://github.com/ciddwd/overlay-translator.git
cd overlay-translator
cp local.properties.example local.properties
# Edit local.properties and point sdk.dir at your local Android SDK
./gradlew installDebug   # installs onto the connected device
```

Requires JDK 17 + Android SDK 35. Opening the project in Android Studio auto-syncs and fills in the Gradle wrapper jar; on a bare CLI environment, run `gradle wrapper --gradle-version 8.10.2` first.

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
- [l0wgear/manga-ocr-2025-onnx](https://huggingface.co/l0wgear/manga-ocr-2025-onnx) · manga-ocr ONNX model
- [kha-white/manga-ocr](https://github.com/kha-white/manga-ocr) · original manga-ocr project
- [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) · GGUF / on-device LLM inference
- [SakuraLLM/SakuraLLM](https://github.com/SakuraLLM/SakuraLLM) · Sakura Japanese-to-Chinese translation model
- [Tencent-Hunyuan/HY-MT](https://github.com/Tencent-Hunyuan/HY-MT) · Hy-MT translation model
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

