<div align="center">

[简体中文](README.md) · **English**

# Screen Translator

**Real-time screen and video translation for Android · text overlays / bilingual video subtitles / read translations aloud**

[![License](https://img.shields.io/github/license/ciddwd/overlay-translator?style=flat-square)](LICENSE)
[![Release](https://img.shields.io/github/v/release/ciddwd/overlay-translator?include_prereleases&style=flat-square)](../../releases)
[![Downloads](https://img.shields.io/github/downloads/ciddwd/overlay-translator/total?style=flat-square)](../../releases)
[![Stars](https://img.shields.io/github/stars/ciddwd/overlay-translator?style=flat-square)](https://github.com/ciddwd/overlay-translator)
[![Issues](https://img.shields.io/github/issues/ciddwd/overlay-translator?style=flat-square)](../../issues)
![Android API](https://img.shields.io/badge/Android-8.0%20%28API%2026%29%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)
[![QQ Group 1059655926](https://img.shields.io/badge/QQ%E7%BE%A4-1059655926-12B7F5?style=flat-square&logo=tencentqq&logoColor=white)](https://qun.qq.com/universal-share/share?ac=1&authKey=%2Fs0%2FaO4mEHsgutzjUnhGIQEWLcAcGPXTefUY2YwdMkPdnHHuB%2FpLZm9hPjcrw6n5&busi_data=eyJncm91cENvZGUiOiIxMDU5NjU1OTI2IiwidG9rZW4iOiJ4b25nS0FvSFQyMko4WjJTMHhGRlIwSnppeVB2eGJCNjFua0FDTGZzNUhEWlY3VkdPcFVaOEdMams0aEY3aFBTIiwidWluIjoiNTcyMjQyOTk4In0%3D&data=j7H7DHUunIEqMXYLZxhTkx-K_LZTTs5aBJS95LT_Y50uQy37d5IiUU2y3gAPcy9CYRzRufvHuTCaSHOQsLTkTw&svctype=4&tempid=h5_group_info)

**No ROOT required, Android 8.0 or later, with on-device options available**, supporting text translation for visual novels, manga, game dialogue, and any on-screen text, plus real-time video speech translation with bilingual subtitles (video translation requires Android 10 or later).

Capture through system screen sharing, Shizuku, accessibility, or Root → on-device or cloud OCR → LLM / machine translation → floating overlay.
Translations can appear beside the original text or be read aloud with a phone voice, a self-hosted voice service, or an online voice.
Live video translation is also available: recognize audio playing on the phone and show source and translated text together as bilingual floating subtitles.

[Website](https://overlaytranslator.top/) · [Install](#-install) · [Usage](#-usage) · [Configuration](#%EF%B8%8F-configuration) · [Contributing](#-contributing) · [Releases](../../releases) · [Issues](../../issues)

</div>

---

## ✨ Features

### 🎯 In one line

For games, manga, and visual novels, tap the floating ball to recognize on-screen text and overlay its translation; during video playback, recognize speech in real time and show bilingual subtitles, with optional read-aloud controls for translations.

### 🖼️ Translate a group of saved images

- Tap **New task** on the home screen to choose several images at once, or share images and screenshots to **Screen Translator** to open a new task directly
- Choose a preset and start the task; processing continues after you leave the task page. The home screen shows the latest or active task, and **Task list** keeps the rest
- The result page shows the source and translation and lets you swipe between images. With **Source-aligned** or **Adapt to screen**, you can also save translated copies that keep the task's font, colors, layout, and text direction

### 🎬 Live video translation

- **Bilingual subtitles from playback audio**: recognize dialogue playing in a video or game and display the source text and translation together as floating subtitles, even when the video has no subtitles
- **Use the same floating ball**: switch to **Speech recognition** mode, tap to start, and tap again to pause or resume. Long-press still opens the existing menu
- **On-device or online recognition**: choose SenseVoice, Zipformer, or Whisper after downloading the corresponding model, or configure Alibaba Cloud, Tencent, Volcengine, or an OpenAI-compatible transcription service. Recognized text uses your selected translation configuration
- **Requirements**: Android 10 or later, recording permission, and screen-sharing permission. This captures app playback audio without using the microphone. Audio availability depends on the system and player allowing capture; subtitle delay depends on the phone, models, translation service, and network. Online recognition uploads audio and may incur service charges

### 🖱️ How to trigger

- **Tap the floating ball**: defaults to translating the whole screen; in **Word-pick mode**, draw around a word or phrase, then fine-tune the selection and tap **Translate** by default. Disable **Precise adjustment** in Settings to recognize and translate immediately on release
- **Long-press the floating ball**: open the arc menu or **Quick panel** to switch between full-screen, word-pick, loop, input, gesture, and speech recognition modes; select a capture region, change languages or presets, open Settings, or return to the app. Arc-menu actions can be reordered and paginated
- **Gesture mode**: draw one continuous stroke and release to run its assigned action. Bind eight gestures independently, with optional edge-swipe, twist, or tilt triggers
- **Input translation**: single-tap the floating ball to translate input-field text into the preset's source language; double-tap for your choice of full-screen or word-pick translation
- **Quick toggle**: start or stop the capture service directly from your phone's Quick Settings panel
- **Loop mode**: **Translate when settled** is the default, translating automatically when the screen stops changing. You can also translate at fixed intervals or wait until all the text has appeared.
- **Volume two-key**: hold **Vol+ and Vol−** together for 0.3 s to trigger; your hands stay on the game (requires enabling the bundled accessibility service)
- **From any other app**: long-press to select some text in any app → tap "Screen Translator" in the system menu → translation card pops up immediately, no need to switch back to this app first

### 🔍 OCR (read the text on screen)

- **On-device**: ML Kit (CJK + Latin) / PaddleOCR / manga OCR — your screenshots never leave the phone; works offline; PaddleOCR supports multiple v5/v6 tiers, with PP-OCRv5 Korean available as a separate choice
- **Automatic OCR settings**: manage recognition methods by language, add languages, choose their models, and check download status
- **Local HTTP OCR**: connect to Umi-OCR / LunaTranslator services on your LAN or PC
- **Cloud**: PP-OCRv6 Online (PaddleOCR AI Studio) / Baidu / Tencent / Youdao — fall back to these when on-device OCR misreads; PP-OCRv6 Online requires an AI Studio Access Token
- When you switch source language, the app checks whether the current OCR engine can read it; if not, it suggests a better one
- First-run setup matches Japanese, Korean, Chinese, and English with the corresponding OCR; other source languages default to recommending PaddleOCR v5 mobile
- Optional orientation detection can route horizontal / vertical / rotated text to a better OCR path, or you can lock the direction manually
- PaddleOCR, manga OCR, and orientation models select 1 / 2 / 4 / 6 CPU inference threads from the device's available cores, capped at 6

### 🌐 Translation engines

- **LLMs**: DeepSeek / ChatGPT / Claude / Zhipu / self-hosted services using OpenAI- or Anthropic-compatible connections, with results shown as they arrive
- **Provider quick setup**: choose ModelScope, OpenRouter, AIHubMix, 302.AI, SCNet, or another listed provider; some services offer mainland China and international addresses
- **On-device translation**: Google ML Kit works offline after downloading language packs. The local model manager supports Sakura, Hy-MT2, Index-Translate, Qwen, LFM, and Gemma models, plus GGUF import; see the model section below
- **Language-direction guard**: source and target cannot be set to the same language; conflicting choices are disabled with an explanation in Settings and the floating quick-switch menu
- **Consistent terminology**: save names, places, organizations, and domain terms globally or per app, either individually or through JSON batch import. Long-press an existing translation to correct the source or translation and keep the correction
- **More consistent over time**: when the same or a similar sentence appears later, the app can reuse a saved translation. Review, search, and manage these entries in the **Translation library**
- **DeepL**: free / Pro plan auto-detected, **supports self-hosted [deeplx](https://github.com/OwO-Network/DeepLX)** (open-source proxy, run on your own server, no key needed); pick between Official / deeplx / Auto-fallback
- **NiuTrans**: choose Flash or Pro; Pro can display translations as they arrive
- **Youdao Pic-Trans**: skips the OCR step entirely — send the screenshot, get back boxes with translations. Great for comics.
- **Google**: no key, free (may require a proxy in mainland China)
- Online engines have a **Test connection** action; DeepL can also show the current quota

### 🔊 Read translations aloud (TTS)

- **Phone system voices**: no account is needed. Use voices already available on the phone and adjust their speed and pitch
- **More voice choices**: use your own compatible voice service, or an online service such as Volcengine, MiniMax, or MiMo; online services may charge
- **What it can read**: source text, translations, word cards, and dictionary details. You can also select part of a horizontal passage and read only that selection
- **Playback controls**: tap the same passage again to pause or resume; tapping another passage switches to it, and failures are explained on screen. Some phone TTS engines do not report the current reading position, so resuming with those voices starts the passage again
- TTS is off by default and does not affect recognition or translation when unused

### 🔤 Word lookup (one tap = mini dictionary)

Not just full-screen translation. Draw a rectangle around a single word or phrase and a card pops up with:

- **Translation** — every engine returns this
- **Pronunciation / part of speech / inflections / synonyms / multiple definitions / example sentence pairs / difficulty notes** — only when an **LLM engine** is selected; rare words, specialist terms, abbreviations, cultural references, and confusing usages are explained separately (Baidu / Tencent / DeepL etc. only return a plain translation)
- **Selection workflow** — fine-tune the box and tap **Translate** by default; disable **Precise adjustment** for release-to-translate. **Remember last selection** is off by default
- A "Copy source" and "Copy translation" button on the card; long-press any text inside to copy too
- In landscape, the close bar stays pinned at the top and both copy actions stay pinned at the bottom; only the content scrolls, clear of system bars and display cutouts
- Great for game / manga / VN vocabulary you don't recognize — one second per word, faster than switching to a dictionary app

**Text extraction**: enable **Extract text only**, then select an area to extract its text without translating it. You can copy the text or have it read aloud.

### 🎨 How the overlay looks

- **Two render modes**: glued to each source box (BLOCKS), or packed into a **draggable / resizable floating window** — perfect for games with on-screen joysticks and buttons; you can lock the window to prevent accidental touches
- **Adapt to screen**: when translations are shown over the original screen, it chooses colors, background, and text size for each area. In comics, the bundled bubble detector can erase source text, repair the local background, and fit horizontal or vertical translations to the detected shape. Low-confidence or failed cases fall back to the existing rectangular renderer, with no separate bubble-model download
- **Unified layout and reading order**: translated text follows OCR-detected horizontal / vertical layout and reading order by default; when disabled, both the layout and LTR / RTL direction must be selected manually, and OCR sorting uses the same resolved direction as rendering
- **Selectable translation blocks**: long-press a block for system text selection, or choose tap-to-open panel mode to select a range or copy the full source / translation
- **5 color themes + visual color picker**: choose background, text, border color, and opacity directly instead of typing ARGB; the picker scrolls on short landscape screens
- **Full text styling**: bold, italic, underline, letter spacing, line spacing, alignment, outline, and shadow can be tuned independently, with a live preview
- **Comic / subtitle optimizations**: sentences split across multiple OCR boxes get merged before translating; vertical Japanese is read right-to-left; tiny ruby-text columns (furigana) next to kanji are filtered out so you don't get duplicate translations; vertical scenes can use vertical translation layout
- **Marquee for long lines**: in single-line mode, long translations scroll horizontally instead of being truncated with "…"
- **Custom translation font**: import `.ttf` files for translated text only; the app UI keeps using the system font, and imported fonts stay available as selectable chips
- **Screen orientation correction**: for situations where the picture's orientation differs from the device's orientation

### 🛠️ Small conveniences

- **English / 简体中文 / العربية UI + Light / Dark / Follow-system theme**, applies instantly
- **Step-by-step first-run guide**: choose UI, source and target languages, display style, everyday / manga use, translation method, and TTS one page at a time; cloud LLM setup includes provider, URL, API key, and model fields, while offline choices list required downloads
- **Interactive floating-ball tour**: after Capture Service is first enabled, it teaches the long-press gesture, every arc-menu action, and menu pagination; **Help** in the home Capture Service card can rerun both setup and the tour
- **Share with friends**: About can share a feature summary, the official source repository, and the latest download page
- **Compact setting choices**: detection profile, merge strength, display mode, and translation-block copy behavior switch directly through button groups
- **In-settings search**: search in either language; new color, font, backup, import, and export settings are indexed too
- **System presets**: built-in bundles such as "offline Japanese manga OCR → Simplified Chinese" set up recognition, translation, and display in one step; the manga preset uses Adapt to screen by default, and lists any models you still need to download
- **API keys per preset**: each preset can store its own keys on this device and switch them along with its settings; keys are never exported
- **English offline dictionary**: import an English (ECDICT) dictionary pack and choose on-device word lookup
- **Background model downloads**: continue after leaving Settings; Android 13 and later requests notification permission before a download starts, and both the app and system notification show the current model and progress, with cancel, retry, and resume support
- **Per-app translation context**: detect the foreground app and apply its terminology; package names always stay on-device, and only the display name is sent when you explicitly enable that option for a context-aware translator
- **Portable settings bundle**: export / import non-sensitive settings, custom presets, and font files in one step; API keys and tokens remain on the current device
- **Translation cache**: during the current process, identical text with the same translation configuration reuses its result for cloud engines and on-device LLMs
- **Auto-saved crash reports**: Java crashes, native crashes, ANRs, low-memory exits, and related failures are surfaced on the next launch as an exportable sanitized report
- **Baseline TalkBack support**: the floating ball, arc menu, language / preset switchers, region picker, word card, and color picker expose labels, state, and action hints
- **Encrypted sensitive settings**: API keys, prompts, mirror URLs and presets are migrated into encrypted local storage to reduce plaintext leftovers
- **Update prompt**: checks for new versions when you open the home screen (at most once a day); offers a direct link if GitHub is unreachable
- **Background-service guides**: shortcuts to auto-start / battery whitelist settings for Xiaomi / OPPO / VIVO / Huawei / Samsung devices that aggressively kill background services
- **Privacy default**: cleartext HTTP is only allowed inside your LAN by default; public hosts must use HTTPS

## 🧩 Project highlights

Screen Translator treats **capture, text recognition, translation, display, and speech** as independently configurable parts:

| Feature | Description |
|---|---|
| **Mix and match recognition and translation** | Read text on the phone or through your own computer or a cloud service, then choose translation separately. One unavailable provider does not disable the whole workflow |
| **Speech is independent from translation** | Any translation method can be paired with a phone voice, your own voice service, or an online voice without reconfiguring recognition and translation |
| **Live screens and saved images** | Use the floating ball to translate what is on screen immediately, or send a group of saved images to the background; both workflows can use your saved presets |
| **Input translation** | Single-tap the floating ball to translate input-field text into the preset's source language; double-tap for your choice of full-screen or word-pick translation |
| **Fully offline or fully self-hosted** | On-device recognition and translation plus an installed offline phone voice can keep screenshots and text on the phone; recognition, translation, and speech services can also run on your own computer or server |
| **Built for games, manga, and visual novels** | Wait for dialogue to finish typing, skip unchanged scenes, prioritize dialogue regions, handle vertical manga, preserve source position and reading direction, and keep per-game names and terms consistent |
| **Open source without configuration lock-in** | Apache-2.0 source; settings, presets, terminology, and fonts can be exported for migration, while API keys stay out of the export |

It is intended for users who want to choose their own recognition, translation, and speech methods while keeping offline use, vertical manga, and self-hosted services available.

## 📸 Screenshots

**Home screen** — Swipe vertically between **Presets / Current status** in the upper section, and horizontally between **Capture service / Batch translation** in the lower section.

| Presets and batch translation | Status and capture service |
|---|---|
| <img src="docs/screenshots/home-batch-translation.png" width="320" alt="Home presets, batch translation, and latest task" /> | <img src="docs/screenshots/home-capture-service.png" width="320" alt="Home status and capture service" /> |

**Live overlay** — Discord rules page, OCR boxes glued to the source text:

| Classic Dark | Paper Light | Adapt to screen |
|---|---|---|
| <img src="docs/screenshots/overlay-discord-dark.png" width="320" alt="Classic dark overlay" /> | <img src="docs/screenshots/overlay-discord-light.png" width="320" alt="Paper light overlay" /> | <img src="docs/screenshots/overlay-discord-adaptive.png" width="320" alt="Discord screen-adaptive overlay" /> |

**Japanese manga — Adapt to screen comparison** — The same vertical Japanese page shown as the original, with Adapt to screen, and with the translated text changed to a horizontal left-to-right layout:

| Original | Adapt to screen | Adapt to screen (horizontal, left-to-right) |
|---|---|---|
| <img src="docs/screenshots/overlay-manga-jp-original.png" width="280" alt="Original Japanese manga page" /> | <img src="docs/screenshots/overlay-manga-jp-adaptive.png" width="280" alt="Japanese manga with Adapt to screen" /> | <img src="docs/screenshots/overlay-manga-jp-adaptive-horizontal-ltr.png" width="280" alt="Japanese manga adapted to the screen with horizontal left-to-right translations" /> |

**In-game** — The same Sandship screen compared as the original, a source-aligned overlay, and Adapt to screen.

| Display | Preview |
|---|---|
| Original, no processing | <img src="docs/screenshots/overlay-game-original.jpg" width="640" alt="Original game screen without processing" /> |
| Source-aligned overlay | <img src="docs/screenshots/overlay-game-source-aligned.jpg" width="640" alt="Game translations aligned to source text" /> |
| Adapt to screen | <img src="docs/screenshots/overlay-game-adaptive.jpg" width="640" alt="Game translations adapted to the screen" /> |

**Comic / subtitle scene** — manga bubbles OCR'd by column, translation glued to source:

| Korean manga | Vertical Japanese manga |
|---|---|
| <img src="docs/screenshots/overlay-manga.png" width="320" alt="Korean manga overlay" /> | <img src="docs/screenshots/overlay-manga-jp.png" width="320" alt="Vertical Japanese manga overlay" /> |


**Floating-window mode** — collects all source / translated lines into one draggable, resizable overlay so translations don't cover game controls (joysticks, action buttons, dialog-advance keys). The window can be **locked**: unlocked shows a close button + bottom resize handle and accepts drag / pinch; locked strips them away, leaving only the text and ignoring every gesture — no more accidental drags during gameplay.

| Unlocked (drag / resize / close) | Locked (gesture-proof, content only) |
|---|---|
| <img src="docs/screenshots/floating-window-unlocked.jpg" width="420" alt="Floating window unlocked" /> | <img src="docs/screenshots/floating-window-locked.jpg" width="420" alt="Floating window locked" /> |

**Arc menu (long-press) & word-lookup card** — Left: the arc menu that fans out when you long-press the ball: loop / re-pick region / switch between "Translate full screen" and "Translate a word" / back to main app (the button order is freely reorderable in settings). Right: the card you get after circling a single word on screen — pronunciation, part of speech, multiple definitions and example sentences, plus separate read-aloud controls for the source, translation, and dictionary content (the full dictionary requires an LLM-based engine).

| Arc menu on long-press | Word-lookup card (dictionary + TTS) |
|---|---|
| <img src="docs/screenshots/arc-menu.png" width="320" alt="Arc menu fanning out from the floating ball" /> | <img src="docs/screenshots/word-select.png" width="320" alt="Word-lookup card with dictionary details and TTS read-aloud controls" /> |

**Translation-block selection / copy / read aloud** — Set the translation-block tap action to **Open selection panel**, then tap any translated block to view its source and translation together. Drag to select a range and use the system **Copy / Select all** actions, or copy either full block with one tap. The speaker button beside each section uses the app's read-aloud feature to play the complete source or translation.

<p><strong><span style="color: red;">Note: Vertical translations cannot currently use long-press to select a short passage for speech. Switch to the tap-to-select mode to read a full source or translation with its speaker button.</span></strong></p>

<p align="left">
  <img src="docs/screenshots/translation-block-selection.png" width="420" alt="Translation-block text selection, copy, and read-aloud panel" />
</p>

**Settings**:

| Language / theme / system presets | Translation backend |
|---|---|
| <img src="docs/screenshots/settings-top.png" width="280" alt="Settings top and system presets" /> | <img src="docs/screenshots/settings-translator.png" width="280" alt="Translation backend settings" /> |
| **OCR engines** | **Orientation model settings** |
| <img src="docs/screenshots/settings-ocr.png" width="280" alt="OCR engine settings" /> | <img src="docs/screenshots/settings-direction-model.png" width="280" alt="Automatic orientation detection and orientation model settings" /> |
| **Overlay style and font** | **Adapt to screen** |
| <img src="docs/screenshots/settings-display.png" width="280" alt="Overlay display and font settings" /> | <img src="docs/screenshots/settings-display-adaptive.png" width="280" alt="Adapt to screen settings" /> |
| **Arc-menu buttons** | **Floating-window mode options** |
| <img src="docs/screenshots/settings-arc-menu.png" width="280" alt="Arc-menu button settings" /> | <img src="docs/screenshots/settings-display-floating.png" width="280" alt="Floating-window mode settings" /> |
| **Floating ball** | **Smart loop translation** |
| <img src="docs/screenshots/settings-floating.png" width="280" alt="Floating ball settings" /> | <img src="docs/screenshots/settings-loop-translation.png" width="280" alt="Smart loop translation settings" /> |

| **Word-select translation / Capture region / Input translation** | **TTS speech settings** |
|---|---|
| <img src="docs/screenshots/settings-word-select.png" width="280" alt="Word-select translation / Capture region / Input translation" /> | <img src="docs/screenshots/settings-tts.png" width="280" alt="TTS engine, system voice, speed, pitch, and test-text settings" /> |

## 📦 Install

1. Grab the latest `ScreenTranslator-x.y.z.apk` from [Releases](../../releases)
2. Tap it on your Android device (first time you'll need to allow "Install unknown apps" in system settings)
3. Grant **Overlay** and **Notification** permissions on first launch

Only **`arm64-v8a` (64-bit ARM)** builds are currently published. 32-bit ARM and x86 devices are not supported. Check the processor architecture in your system information or device specifications before downloading.

Each APK ships with a `.sha256` so you can verify integrity against `Get-FileHash` / `sha256sum`.

## 🚀 Usage

1. Complete setup and tap **Start capture service**. The app automatically selects an available capture method by default; grant screen-sharing permission when using system capture
2. Switch to any game / VN / manga app
3. Tap the floating button → the translation appears when recognition and translation finish; the wait depends on the phone, the screen, and the selected services
4. Long-press the floating button to open the arc menu or Quick panel, switch among six modes, select a capture region, or change languages and presets
5. By default, tap a translation block to hide it or long-press to select and copy; Settings can instead make taps open the source / translation copy panel

Optional:

- Enable the accessibility service so **holding Vol+ and Vol- together for 300 ms** acts as a global trigger. Screen Translator's own primary overlays expose TalkBack labels, state, and action hints.
- Install and authorize [Shizuku](https://github.com/RikkaApps/Shizuku) to use its capture method. Android 11+ can also use authorized accessibility capture, and already-rooted devices can use Root capture. Root is optional.
- Pick a system preset at the top of settings, such as "Offline Japanese manga OCR → Simplified Chinese". If a required model is missing, the preset card lists it and offers a download action.
- For single-word lookup, switch the floating-ball action to **Word-pick mode** from the arc menu, then draw around the word / phrase. You can also select text in another app and invoke Screen Translator from the system selection menu.
- To hear source text or translations, enable TTS in Settings and choose a voice, then tap the speaker button beside the text.
- Add the Screen Translator tile to your phone's Quick Settings panel to start or stop the capture service directly.

### Live video translation

1. In **Speech recognition** settings, choose the recognition method and language, download an on-device model or configure an online service, and set the translation target language
2. Start the capture service and switch to **Speech recognition** through the arc menu or Quick panel
3. Play a video, tap the floating ball, and grant the requested permissions to see bilingual subtitles. Tap again to pause or resume

If no text appears, check that the video is playing audio and that the selected model or service supports its language. Some players restrict audio capture.

### Input translation

Switch to **Input translation** in the arc menu or Quick panel, then tap the input field you want to translate and enter your text:

- **Single-tap the floating ball**: translate the input text into the preset's source language and replace the original content
- **Double-tap the floating ball**: run full-screen or word-pick translation, as selected in the **Input translation** card in Settings

Accessibility permission is required. Input text is read and replaced only when you tap the floating ball. Password-type fields are not read.

## ⚙️ Configuration

Open the app and tap **Settings**. The top of the settings page lets you switch **App language** and **Theme**; any section is reachable through the search icon, matching both Chinese and English keywords.

### Capture methods

- Automatic selection uses an available method by default. Choose system capture, Shizuku, accessibility, or Root manually through **Capture method** on the home screen
- System capture requests Android screen-sharing permission. Shizuku and Root need their own authorization; accessibility capture requires Android 11+ and this app's accessibility service
- **Capture region** limits the recognition area, with dragging, resizing, border styles, and automatic hiding. It updates to the current window dimensions when orientation changes

### OCR engines

| Engine | Good for | Notes |
|---|---|---|
| **On-device** ML Kit (auto / latin / ja / zh / ko) | Default; Japanese / Chinese / Korean / Latin | Offline, on-device |
| **On-device** PaddleOCR PP-OCRv5 / v6 | Multilingual dense text, UI buttons, horizontal Chinese / English | Supports v5 mobile and v6 tiny / small / medium; models must be ready — see below |
| **On-device** PP-OCRv5 Korean | Korean text | A separate Korean recognition model selectable in Settings; download the model before first use |
| **On-device** manga OCR | Japanese manga, vertical bubbles, hand-drawn fonts | Uses manga-ocr ONNX and reuses DBNet detection; ~140 MB, downloadable or importable |
| **Local HTTP** Umi-OCR | You already run [Umi-OCR](https://github.com/hiroi-sora/Umi-OCR) on a PC / LAN server | Point the app at `http://<host>:<port>/api/ocr`; screenshots stay on your LAN |
| **Local HTTP** LunaTranslator | You already run [LunaTranslator](https://github.com/HIllya51/LunaTranslator) OCR on a PC / LAN server | Fill in the LunaTranslator OCR HTTP endpoint |
| **Cloud** PP-OCRv6 Online | Chinese, Japanese, and Latin-script languages | Uses the PaddleOCR AI Studio asynchronous `PP-OCRv6` jobs API; requires an AI Studio Access Token |
| **Cloud** Baidu OCR | Fallback when ML Kit / Paddle miss | Needs API Key + Secret, pay-per-call; 5 endpoints (basic / with-position / accurate / accurate-with-position / web-image); image size / aspect-ratio limits |
| **Cloud** Tencent OCR | Same | Needs SecretId + SecretKey; 3 endpoints: GeneralBasic / GeneralAccurate / **RecognizeAgent** (LLM agent, integrated with ParagNo paragraph grouping) |
| **Cloud** Youdao OCR | Simple one-tap | Needs App ID (API Key) + App Secret; `langType` auto-derived from source language, no separate picker |

<details>
<summary><strong>On-device OCR benchmark</strong></summary>

Test conditions:

- Device: Qualcomm Snapdragon 8 Gen 3 with 16 GB RAM
- Test image: the same full-screen `1440×3200` Japanese manga screenshot for every model
- Preprocessing and text merging disabled; Debug build with detailed performance logging enabled
- “OCR time” is the engine's recognition time. “Complete pipeline” also includes capture, orientation detection, any required model switch, and rendering.
- These measurements come from one device and one image. They compare on-device models within this project and are not universal performance estimates.

#### Speed priority

| OCR model | OCR time | Complete pipeline | Result on this test image |
|---|---:|---:|---|
| PP-OCRv5 Mobile | 1.17 s average | 2.30 s average | 34 segments; reasonable speed, but many wrong, missing, or garbled characters |
| PP-OCRv6 Tiny | 0.65 s | 2.00 s¹ | 29 segments; fastest, but Japanese recognition was largely unusable |
| PP-OCRv6 Small | 0.84 s | 2.51 s¹ | 35 segments; best overall PaddleOCR result, with most body text recognized correctly |
| PP-OCRv6 Medium | 3.60 s | 6.36 s¹ | 35 segments; recovered some body text, but still had recognition and splitting errors |
| Manga OCR | 5.54 s | 6.45 s | 17 complete bubbles; best vertical grouping, sentence integrity, and overall accuracy |

¹ The complete pipeline includes switching from and loading the previous PaddleOCR model: about 0.44 s for Tiny, 0.77 s for Small, and 1.80 s for Medium.

#### Accuracy priority

| OCR model | OCR time | Complete pipeline | Compared with Speed priority |
|---|---:|---:|---|
| PP-OCRv5 Mobile | 3.08 s | 4.24 s | Found 5 more segments, but added false positives and garbled text |
| PP-OCRv6 Tiny | 1.81 s | 3.02 s | Found more boxes, but Japanese remained largely unusable |
| PP-OCRv6 Small | 4.01 s | 5.35 s | Recovered a little small text, while adding several false positives |
| PP-OCRv6 Medium | 16.85 s | 18.16 s | Kept only 2 more segments at a very high time cost |
| Manga OCR | 5.73 s average | 7.00 s average | 21 segments; recovered more small text and improved the difficult bottom area, with some added noise |

For this image, **PP-OCRv6 Small with Speed priority** offered the best balance of speed and Japanese recognition quality. Use **Manga OCR** when vertical manga layout and accuracy matter more. None of the PaddleOCR Accuracy profiles produced a consistent accuracy gain in this test.

</details>

<details>
<summary><strong>On-device translation benchmark</strong></summary>

Test conditions:

- Device: Qualcomm Snapdragon 8 Gen 3 with 16 GB RAM
- The same 18 Japanese OCR segments translated into Simplified Chinese
- “Disable translation cache” enabled in Developer options; both runs reported zero cache hits
- CPU inference at TG=6 / PP=6; native JNI B4 batching in 5 groups (4+4+4+4+2)
- The app was force-stopped before each model run. Opening floating translation automatically loaded and prewarmed the selected model.
- “First shown” and “All 18 complete” start at translation batch submission and exclude OCR time. Results were collected from a Debug build.

| On-device model | Model file | Cold prewarm | First shown | All 18 complete | Result on this test text |
|---|---:|---:|---:|---:|---|
| Sakura 1.5B (Q5_K_S) | ~1.20 GB | 2.99 s | 3.40 s | 10.30 s | More natural Chinese, but one negation was reversed and one time expression was mistranslated |
| Hy-MT2 1.8B (Q4_K_M) | ~1.08 GB | 2.38 s | 3.51 s | 10.84 s | Handled negation and the time expression better, but had weaker context, long-sentence, and Chinese phrasing quality |

Sakura showed its first batch about 0.11 s sooner and finished all segments about 0.54 s sooner. Hy-MT2 completed cold prewarming about 0.61 s sooner. Sakura produced more natural Chinese in this sample, while Hy-MT2 handled some semantic details more accurately. Hy-MT2 used a 512 MiB KV buffer versus Sakura's 224 MiB, which matters on devices with less memory.

Supplemental manga sample (Japanese → Simplified Chinese):

- The same screen and the same 13 Manga OCR segments were used. One digits-only segment was preserved directly, leaving 12 segments for translation.
- Timing starts when the translation batch begins and excludes OCR. The digits-only passthrough is not counted as the first real translation.
- Sakura was cold-started after force-stopping the app. Its log reported zero cache hits, TG=6 / PP=6, and three native JNI B4 groups.
- ML Kit was tested after switching engines in the same app process, with its Japanese and Chinese language models already downloaded. It has no separate model-prewarm stage; initialization overhead is included in the first real translation time.

| On-device translator | Model storage | Model prewarm | First real translation | All 12 translations complete | Result on this test text |
|---|---:|---:|---:|---:|---|
| Sakura 1.5B (Q5_K_S) | ~1.20 GB | 3.66 s | 2.06 s | 7.15 s | Better overall quality, with some semantic inaccuracies |
| Google ML Kit (ja → zh-CN) | Japanese and Chinese models, ~30 MB each | None | 0.32 s | 2.68 s | Much faster, but weaker overall quality |

On this sample, ML Kit produced the first real translation about 1.74 s sooner and completed all translations about 4.47 s sooner. Sakura's Chinese quality was clearly better for manga text that depends on context and character voice.

</details>

**PaddleOCR models**: the first use requires a download or local import. The default is **PP-OCRv5 mobile**. You can switch versions to suit the screen and language:

- **v5 mobile**: the default, supporting Simplified Chinese, Traditional Chinese, English, and Japanese
- **v6 tiny**: the smallest and fastest, with many Latin-script languages but no Japanese support
- **v6 small**: a balance of speed and accuracy, with Japanese support
- **v6 medium**: larger and slower, for faster devices where a longer wait is acceptable

**PP-OCRv6 Online**: this is a cloud recognition service. It needs an AI Studio Access Token but does not need the on-device models above. Screenshots are sent to PaddleOCR's online service when it is used.

**Orientation models**: these detect rotated screens and upside-down text. They are included with the app, so no extra download is needed when they show as ready.

**Manga OCR models**: the first use requires a download or local import. The main public source is Hugging Face; if it is unavailable on your network, use a proxy, custom mirror, or local import.

**Downloads and ready state**: PaddleOCR, manga OCR, orientation, and offline translation models can download in the background. Downloads continue after leaving Settings, and both the app and system notification show progress. You can cancel at any time; interrupted downloads retry and continue from where they stopped. A model can be used when it shows as ready. Delete the installed model first if you want to replace it.

**Automatic orientation detection**: enabled by default. It chooses horizontal or vertical text from the screen and language. If it gets a page wrong, select horizontal, vertical Japanese, or another direction manually.

**Detection speed and accuracy**: "Speed" is the default. Switch to "Accuracy" when tiny, faint, or narrow vertical text is missed, but expect a longer wait. Most users should leave the advanced OCR settings unchanged; adjust them gradually only if text is repeatedly missed or too many false detections appear.

**On-device performance**: local recognition and translation adjust automatically to the phone. Faster phones wait less, while entry-level devices may take longer; there is no need to set thread counts manually.

**Baidu OCR caveats**: image limits are *longest side ≤ 4096 px, shortest side ≥ 15 px, aspect ratio 1:4–4:1, base64 < 4 MB*. The first two are handled automatically (downscale + JPEG quality fallback); aspect ratio out of range can only be fixed by shrinking the capture region.

### Translation engines

Choose by use case first; you do not need to read every setting before getting started:

| Engine | Best for | What you need | Before you use it |
|---|---|---|---|
| **Local GGUF models** | Offline translation, choosing a model for your languages and phone | Download or import a GGUF file in the model manager | Language support, memory needs, and licenses vary; per-model configuration and test translation are available |
| **Google ML Kit (on-device)** | Fast offline translation without an account | Download the required language packs on first use | Usually fast, but game dialogue and manga context may be less natural than an LLM |
| **OpenAI-compatible** | Existing DeepSeek, OpenAI, Zhipu, SiliconFlow, Ollama, or compatible services | Base URL, API key, and model name | Supports streaming and custom prompts; pricing and rate limits depend on the provider |
| **Anthropic-compatible** | Existing Claude or another service with Anthropic support | Service URL, API key, and model name | Supports streaming and custom prompts; pricing and rate limits depend on the provider |
| **DeepL / DeepLX** | Conventional machine translation or an existing self-hosted DeepLX service | DeepL Auth Key or a DeepLX service URL | Official DeepL selects free / pro automatically; Test connection can show quota |
| **NiuTrans** | General text translation | API key; Flash also requires an App ID | Choose Flash or Pro; Pro can display translations as they arrive |
| **Youdao PicTrans** | Comics, full images, and screens with many text boxes | Youdao App ID + App Secret | Translates the screenshot directly and does not use the OCR engine selected above |
| **Google** | A quick trial without entering a key | Nothing | Uses an unofficial endpoint; usually needs a proxy in mainland China and may be rate-limited or stop working |
| **Volcengine** | Long loop-translation sessions | AK / SK with Machine Translation enabled | Keep the default region; this is an online service |
| **Baidu Translate** | General text translation | Baidu Translate APPID + secret | Separate product and account system from Baidu OCR |
| **Tencent Translate** | General translation or an existing Tencent OCR setup | Tencent SecretId + SecretKey | Reuses the same credentials as Tencent OCR |

Every online engine has a **Test connection** action. Use it before switching to your game.

#### Translation-mode timing reference

| Engine | Fast | Same screen | Continuous | Suggested use |
|---|---|---|---|---|
| OpenAI-compatible | About 1–3 s, consistent | About 2–3 s, best overall in this sample | About 2–3 s, can reference the previous screen | When online; same-screen mode is a useful starting point |
| Sakura | About 7–9 s, fairly consistent | About 5–11 s; long pages occasionally need a retry | About 6–10 s, can continue dialogue across screens | Offline Japanese manga; start with Fast, then try contextual modes for continuity |
| Hy-MT2 | About 5–8 s | About 19–35 s | About 20–71 s | Offline translation; Fast is the suggested starting point |

> These are the existing measurements from one phone and fixed screens, not new v0.4.8 benchmarks. Actual speed depends on the device, text volume, network, and model. Fast minimizes waiting; Same screen includes other text on the current screen; Continuous also includes previous-screen context, which can increase latency and carry earlier mistakes forward.

<details>
<summary><strong>How do I fill in an OpenAI-compatible service?</strong></summary>

- **Quick setup**: choose ModelScope, OpenRouter, AIHubMix, 302.AI, SCNet, or another listed provider; some services offer mainland China and international addresses
- **Base URL**: use the provider's OpenAI-compatible endpoint, usually ending in `/v1/`
- **API key**: use the key issued by the provider; it may be left blank for a self-hosted service without authentication
- **Model name**: copy the exact name from the provider console or your self-hosted service instead of guessing from an example
- **Prompt template**: the default is written for conversational game translation. You may customize it; untouched prompts follow the UI language, while edited prompts are never overwritten automatically

</details>

### Local model management

Local models are downloaded or imported separately and are not bundled in the APK. Choose for your translation task and available memory:

| Model | Purpose and language direction |
|---|---|
| Sakura-1.5B-Qwen2.5 | Japanese manga and visual novels → Simplified Chinese, with a fixed language direction |
| Hy-MT2-1.8B / Index-Translate-2B | Dedicated multilingual translation; direction follows your settings |
| TranslateGemma-4B-it | Dedicated multilingual translation; direction follows your settings |
| Qwen3-0.6B / LFM2.5-1.2B-Instruct | General instruction models; compare their translation results using the test action |
| Gemma-3-4B-it / Gemma-4-E2B-it / Gemma-4-E4B-it | General instruction models with larger files and runtime memory needs |

- **Download and import**: Hugging Face, hf-mirror, custom mirrors, and local GGUF import are supported. Background downloads offer progress, cancellation, retry, and resume; check the app for file sizes. Large downloads warn before starting on cellular or an unknown network type
- **Per-model settings**: context window, output-token limit, sampling, and performance settings are saved separately. Dedicated translation models use their official settings by default; unlock them to customize or restore their official settings
- **Test translations**: compare models with the same source text, considering first-translation time and accuracy
- **Model preparation**: capture startup prepares selected, downloaded models. Supported translators try to process same-screen text together, falling back to individual blocks when needed
- **Performance**: initial loading, longer contexts, and larger models increase latency and memory use. Inference is mainly CPU-based; Vulkan is experimental and may not work or run faster on every device
- **Device-local data**: model files, import records, and per-model settings are excluded from settings exports. Download or import and configure them again on a new device
- **Model licenses**: different models have different terms; see [NOTICE](NOTICE). The app's Apache-2.0 source license does not replace model licenses

### Read translations aloud (TTS)

To hear translations aloud, enable TTS in Settings and choose a voice option:

| Option | Best for | What to know |
|---|---|---|
| **Phone system voice** | Using the voices already on your phone | No account or service charge is required. You can choose an installed voice and adjust its speed and pitch |
| **Self-hosted voice service** | People already running a compatible voice service on a PC or local network | Enter its address and keep the voice and service under your own control |
| **Volcengine** | People with an active Volcengine speech account | Enter your account details and use a preset or cloned voice. Provider usage may incur charges |
| **MiniMax** | Finding more voices or cloning and designing your own | Search, preview, and select voices; upload a recording to clone a voice, or describe the new voice you want |
| **MiMo** | People already using the MiMo speech service | Supports preset voices, voice design, and voice cloning, plus plain-language instructions for speaking style |

- **How to speak text**: source text, translations, and word cards have speaker buttons. You can select part of a horizontal passage and read only that selection; for vertical translations, use the tap-to-select mode to read a full block
- **Pause and resume**: tap the same text again to pause, then tap once more to continue. Some phone TTS engines do not provide reading-position updates, so they restart the passage instead; tapping different text always starts the new content
- **When voices are too quiet**: self-hosted services, Volcengine, MiniMax, and MiMo can be boosted in Settings. Very high values may sound distorted. Phone system voices do not support this setting
- **Charges and problems**: phone system voices are free. The app warns before using other services that may charge. Missing voices, incorrect account settings, and playback failures are explained on screen
- **MiniMax voices**: the voice page has Find, Clone, and Design tabs. Search by language or voice name; tapping a voice asks for confirmation before selecting it. Creating, using, and deleting voices also require confirmation

### Terminology and app context

- **Terminology library**: store a source term, preferred translation, language pair, category, case rule, and enabled state, scoped globally or to one app; filters cover source, translation, app, and category
- **Foreground app detection**: choose Auto, Accessibility, Usage access, or Global only. Package names are used only for on-device terminology matching and are never sent to translation services; "Send app name to the model" is a separate opt-in
- **Supported engines**: app names and terminology context currently apply only to OpenAI-compatible, Anthropic-compatible, and on-device LLM translation; plain machine-translation engines keep their existing request format

#### Import terms in bulk

Tap **＋** in the terminology list, choose **Batch import**, and select a JSON file. Each entry only needs a source term and its preferred translation:

```json
[
  {"source": "梓ちゃん", "target": "梓酱"},
  {"source": "先輩", "target": "前辈"}
]
```

Chinese field names are also supported:

```json
[
  {"原文": "梓ちゃん", "译文": "梓酱"}
]
```

- Preview, filter, and deselect entries before importing; choose whether to skip or overwrite matching existing terms
- Different source terms may share the same translation. If one source term has different translations in the file, select which one to keep first
- Each file may contain up to 5,000 entries and must not exceed 2 MB

### Dictionary library

Manage lookup options in **Translation library → Dictionary library**:

- **Online lookup**: use the currently configured cloud LLM; an OpenAI-compatible or Anthropic-compatible translation engine is required
- **Offline lookup**: import an English (ECDICT) `.bin` dictionary pack made for Screen Translator to look up words on your phone
- **Show definitions when tapping source text**: turn this feature on or off here

### Loop translation

- **Trigger mode**: **Translate when settled** is the default; **Fixed interval** and **Wait for text completion** are also available
- **Translate when settled**: translate after the screen stops changing and update the translation after a page turn.
- **Animated backgrounds**: on-device OCR can verify that text remains unchanged even while the frame animates; cloud OCR uses frame stability only, avoiding extra paid verification requests
- **Dialogue region**: choose Auto, Lower screen first, or Anywhere, then decide whether to translate only that region or use it for stability while translating all text recognized in the first frame
- **Duplicate skipping**: exact frame matches skip both OCR and translation; similar frames still verify whether OCR text changed, with an adjustable sensitivity threshold

### Display

- **Render mode**: BLOCKS (per OCR box, glued to source) / **Floating window** (a draggable, resizable overlay — see the [screenshots section](#-screenshots))
- **Placement** (BLOCKS only): below / overlap / above + pixel-level x/y offset
- **Translation layout**: "Follow recognized text layout" is enabled by default, so horizontal / vertical layout and reading order jointly drive OCR-block sorting and translated-text rendering; turn it off to explicitly choose horizontal / vertical and LTR / RTL, including vertical manga and horizontal RTL
- **Block interaction**: the default is tap-to-close plus long-press range selection; an alternate mode makes taps open a panel where you can select or copy the full source / translation
- **Floating window**: switch between "source + translation" and "translation only" content modes; **lock** the window to disable dragging and resizing during gameplay; a "Reset position / size" button restores defaults in one tap
- **Screen orientation correction**: for situations where the picture's orientation differs from the device's orientation
- **Floating-ball opacity**: adjust it in the **Floating button** card in Settings
- **Theme**: 5 presets + a visual custom color picker for independent background / text / border color and opacity; borders support solid / dashed / dotted / double / groove styles
- **Text style**: bold / italic / underline, letter spacing, line spacing, left / center / right alignment, outline width and color, plus shadow radius / offset / color
- **Font**: import `.ttf` files for the translation layer and preview only; imported fonts are kept as horizontally selectable chips, and reselecting an existing font does not reorder them
- **Avoidance & merge**: collision detection clamps translation width so it doesn't bleed into neighbouring OCR boxes; OCR-side box merging offers **Conservative / Standard / Aggressive** strengths — Conservative suits VNs / dense passages, Standard fits most scenes, Aggressive suits comic bubbles split into many columns (may occasionally merge adjacent bubbles)

### Presets and floating controls

- **System presets**: built-in bundles such as "offline Japanese manga OCR → Simplified Chinese" set up recognition, translation, language, and display in one step. The manga preset uses Adapt to screen by default, and lists missing models for download.
- **Custom presets**: save the current settings as a preset. A preset only shows as applied when all key settings still match; changing any key field returns the card to the unsaved state. Each preset can store its own API keys on this device and switch them along with its settings; keys are never exported.
- **Arc-menu buttons**: reorder rows arranged as drag handle → icon → name; choose 2–6 buttons per page. The last slot becomes **Next page** when more actions remain. Existing mode, capture-region, language, preset, Settings, and open-app actions are available
- **Quick panel**: choose between the arc menu and Quick panel for long-press opening. The panel has six modes, language direction, and four shortcuts, with portrait and landscape layouts. **Arc-menu buttons** settings are unavailable while it is enabled
- **Floating-ball settings**: size and opacity remain on the main settings page. Docking, menu buttons, trigger methods, and gesture bindings each open a dedicated page
- **Gesture bindings**: assign actions to up, down, left, right, right-then-down, down-then-right, S, and circle gestures. Tap the ball to draw, or enable edge-swipe, twist, and tilt triggers with configurable actions and sensitivity

### Backup and migration

- **Complete settings bundle**: `.otsettings` includes portable non-sensitive settings, custom presets, terminology entries, and imported `.ttf` fonts for migration or sharing
- **Secrets stay on-device**: API keys, tokens, secrets, access IDs, and app IDs are excluded; importing preserves credentials already stored on the destination device
- **Legacy preset support**: older `.otpresets` files remain importable; the new bundle validates imported data and de-duplicates preset names
- **Models stay local**: model files, local GGUF import records, and per-model settings are excluded. Download or import the required models and configure them on the destination device

### Accessibility and diagnostics

- **TalkBack**: primary overlay entry points expose readable labels, toggle state, and double-tap / long-press actions; region selection announces state and dimensions, while complex menus are read row by row
- **Word card**: the top close bar and bottom copy bar stay fixed while the body scrolls; landscape sizing subtracts system bars and cutout insets so actions are not clipped
- **Crash logs**: in addition to Java exceptions, Android 11+ imports system-reported native crashes, ANRs, low-memory exits, and signal exits; settings snapshots are sanitized and system traces are read with a size limit
- **Developer diagnostics**: temporarily disable the translation cache so every test translates again; elapsed time shows per-item translation time by default, with an optional batch-cumulative completion time

## ⚠️ Known limitations

- Android 14+ system capture requires screen-sharing authorization for each new session. Shizuku, accessibility, and Root capture use their own permissions; availability depends on the system and authorization state.
- Some ROMs (Xiaomi / OPPO / VIVO) kill background services or block overlays by default. You need to whitelist battery + allow background start manually. The app provides in-product shortcuts.
- Anti-cheat networked games may flag MediaProjection as a screen-recorder cheat — this project is for single-player / VN / manga only.
- Screens marked `FLAG_SECURE` (some banking / video apps) capture as black; this project does not bypass it.
- On-device OCR time varies with the phone, selected model, and screen complexity. Region selection can help on slower devices.
- Local model files range from hundreds of MB to several GB; Wi-Fi is preferable for the initial download. Runtime memory is not the same as file size, and low-memory devices may fail to load a model reliably.
- On-device inference is mainly CPU-based and does not yet use Qualcomm QNN / NPU. Vulkan is experimental and is not guaranteed to work or run faster on every device.
- The public manga-OCR source is mainly Hugging Face. If it is unreachable from your network, use a proxy, custom mirror, or local import.
- TalkBack covers the primary overlay workflow, but full-screen OCR text is not yet exposed as individually navigable system accessibility nodes.

## 🗺️ Roadmap

### ✅ Available now

| Feature | What users can do |
|---|---|
| **Floating translation** | Tap the floating ball over a game, manga, or visual novel and show translations beside the source or in a separate floating window |
| **Live video translation** | Recognize dialogue playing in videos or games and show the source text and translation together as bilingual floating subtitles; choose on-device or online recognition and pause / resume as needed |
| **Input translation** | Single-tap the floating ball to translate input-field text into the preset's source language; double-tap for your choice of full-screen or word-pick translation |
| **Quick panel and gestures** | Six modes, four shortcuts, language controls, and eight independently configurable gesture bindings |
| **Local model management** | Download or import GGUF files, configure each model, and compare translations using the same source text |
| **Quick toggle** | Start or stop the capture service directly from your phone's Quick Settings panel |
| **Batch image translation** | Select multiple saved images and let the task continue after leaving the page; review progress, source text, and translations, then save translated images with the task's text style |
| **Adapt to screen** | Choose colors, background, and text size automatically; comic scenes use bundled bubble detection, source-text erasure, local repair, and shape-aware layout with a safe fallback |
| **Automatic loop** | Translate when the screen settles by default, or at fixed intervals or after text completion; skip unchanged scenes and configure the dialogue region |
| **Multiple OCR choices** | Use on-device ML Kit / PaddleOCR / manga OCR, including a separate PP-OCRv5 Korean option; connect to OCR on your LAN, or use Baidu, Tencent, Youdao, and PP-OCRv6 Online |
| **Horizontal and vertical text** | Detect rotated, horizontal, and vertical manga text; follow the recognized layout or manually choose layout and reading direction |
| **Screen orientation correction** | For situations where the picture's orientation differs from the device's orientation |
| **Multiple translators** | Google ML Kit, local GGUF models, OpenAI / Anthropic-compatible services, DeepL, NiuTrans, Google, Volcengine, Baidu, Tencent, and Youdao PicTrans |
| **Provider quick setup** | Choose ModelScope, OpenRouter, AIHubMix, 302.AI, SCNet, or another listed provider; some services offer mainland China and international addresses |
| **Copy translations** | Long-press to select a range, or tap a block to open a panel and copy part of the text, the full source, or the full translation |
| **Translation speech (TTS)** | Read source text, translations, and word-card content with phone system voices, a self-hosted service, Volcengine, MiniMax, or MiMo; pause / resume, speak selected text, and manage voices |
| **Word lookup** | Select a word or phrase for a translation; LLM engines can also return pronunciation, part of speech, inflections, synonyms, definitions, examples, and usage notes |
| **English offline dictionary** | Import an English (ECDICT) dictionary pack and choose on-device word lookup |
| **Consistent names and terms** | Save global or per-game names, places, and terminology so wording stays consistent across scenes; import JSON files with preview, filtering, and entry selection |
| **Model downloads** | Download on-device models in the background, view progress in the app and notification, cancel, retry, or resume interrupted files |
| **Faster local translation** | Prepare offline models after capture starts and handle several text blocks together when possible to reduce waiting |
| **Customization and migration** | Adjust floating-ball opacity and translation colors, fonts, size, outline, and shadow; export settings, presets, terminology, and fonts for another device |
| **Assistive features** | Trigger with both volume keys, use TalkBack action hints, export crash logs, and open background-running guides for aggressive Android ROMs |
| **Broader Shizuku compatibility** | Use Sui, official Shizuku, Nightzuku, thedjchi/Shizuku, or Shizuku+ for authorization and screen capture |

### 📋 Next directions

These are user-facing improvements we want to continue working on, not a fixed release order:

| Direction | Intended experience |
|---|---|
| **Floating translation history** | Revisit screens and dialogue translated with the floating ball instead of losing the last line after switching scenes |
| **Character and speaker recognition** | Better identify who is speaking, reduce mistakes in names, relationships, and pronouns, and make ongoing dialogue sound more natural |
| **Long-term on-device model improvements** | Keep improving downloads, startup time, memory use, heat, battery life, and device compatibility across all on-device models, while exploring speech and more features that can run locally on phones |
| **System assistant and agent integration** | Track [Android AppFunctions](https://developer.android.com/ai/appfunctions) and [A2A](https://a2a-protocol.org/latest/), then explore starting translation, speech, and common actions through system assistants or other agents as platform support matures |
| **Deeper accessibility** | Make full-screen OCR text and dynamic translations individually navigable with a screen reader |

On-device model improvements are an ongoing plan that will progress over time as models and phones change, rather than a one-release task.

## 🤝 Contributing

Contributions of any kind — bug fixes, features, UI polish, translations, doc tweaks — are welcome.

If you plan to work on an issue yourself, please leave a comment on it beforehand to let others know, so we can avoid duplicating effort. If no one has said they will take it on, I will generally work on it as my schedule allows.

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

- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) · OCR and orientation models; [official v5 ONNX](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx) / [v6 models](https://huggingface.co/collections/PaddlePaddle/pp-ocrv6)
- [l0wgear/manga-ocr-2025-onnx](https://huggingface.co/l0wgear/manga-ocr-2025-onnx) · manga-ocr ONNX model
- [kha-white/manga-ocr](https://github.com/kha-white/manga-ocr) · original manga-ocr project
- [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) · GGUF / on-device LLM inference
- [SakuraLLM/SakuraLLM](https://github.com/SakuraLLM/SakuraLLM) · Sakura Japanese-to-Chinese translation model
- [Tencent-Hunyuan/HY-MT](https://github.com/Tencent-Hunyuan/HY-MT) · Hy-MT translation model
- [IndexTeam](https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF) / [Qwen](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF) / [Liquid AI](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF) · optional local models
- Google [Gemma 3](https://huggingface.co/ggml-org/gemma-3-4b-it-GGUF), [Gemma 4](https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf), and [TranslateGemma](https://huggingface.co/SandLogicTechnologies/translategemma-4b-it-GGUF) · optional local models; see NOTICE for GGUF conversion sources
- [ogkalu/comic-text-and-bubble-detector](https://huggingface.co/ogkalu/comic-text-and-bubble-detector) · bundled comic text and bubble detection
- [Shizuku](https://github.com/RikkaApps/Shizuku) · privileged channel without ROOT
- [ML Kit](https://developers.google.com/ml-kit) · Google's on-device OCR and translation
- [ONNX Runtime](https://onnxruntime.ai/) · on-device inference engine
- [Jetpack Compose](https://developer.android.com/jetpack/compose) / [Material 3](https://m3.material.io/) · UI stack
- [Hilt](https://dagger.dev/hilt/) · dependency injection
- [Retrofit](https://github.com/square/retrofit) + [OkHttp](https://github.com/square/okhttp) · networking
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) · JSON
- [Timber](https://github.com/JakeWharton/timber) · logging

### Translation Contributors

| Language | Coverage at contribution | Contributor | Source |
| --- | --- | --- | --- |
| Arabic (العربية) | **81.9% (1,985 / 2,425)** | [Maxaseel](https://github.com/Maxaseel) | [Issue #31](https://github.com/ciddwd/overlay-translator/issues/31) |

### Contributors

<a href="https://github.com/ciddwd/overlay-translator/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=ciddwd/overlay-translator" />
</a>

(Will appear automatically after the first PR is merged.)

### Inspiration

PC tools in the VNR / Visual Novel Reader family have served galgame / VN players for years. This project carries that pipeline to Android, redesigned for the phone (battery, ROM constraints, touch UI).

## Star History

<a href="https://www.star-history.com/?repos=ciddwd%2Foverlay-translator&type=date&logscale=&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=ciddwd/overlay-translator&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=ciddwd/overlay-translator&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=ciddwd/overlay-translator&type=date&legend=top-left" />
 </picture>
</a>

## 📄 License

Code is licensed under [Apache-2.0](LICENSE). Models, dictionary data, and third-party dependencies retain their own licenses; see [NOTICE](NOTICE). The in-app open-source notices contain the same copy.
