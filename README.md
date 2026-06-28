<div align="center">

**简体中文** · [English](README.en.md)

# 屏译 · Screen Translator

**Android 屏幕实时翻译 · 截屏 → OCR → 翻译 → 悬浮窗叠加**

[![License](https://img.shields.io/github/license/ciddwd/overlay-translator?style=flat-square)](LICENSE)
[![Release](https://img.shields.io/github/v/release/ciddwd/overlay-translator?include_prereleases&style=flat-square)](../../releases)
[![Downloads](https://img.shields.io/github/downloads/ciddwd/overlay-translator/total?style=flat-square)](../../releases)
[![Stars](https://img.shields.io/github/stars/ciddwd/overlay-translator?style=flat-square)](../../stargazers)
[![Issues](https://img.shields.io/github/issues/ciddwd/overlay-translator?style=flat-square)](../../issues)
![Android API](https://img.shields.io/badge/Android-8.0%20%28API%2026%29%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)

通过 MediaProjection / Shizuku 截屏 → 端侧或云端 OCR → LLM / 机器翻译 → 悬浮窗叠加显示。
无需 ROOT，单机可用，面向视觉小说、漫画、游戏对话等任意屏上文字的实时翻译。

[安装](#-安装) · [使用](#-使用) · [配置](#-配置) · [参与开发](#-参与开发) · [Releases](../../releases) · [Issues](../../issues)

</div>

---

## ✨ 功能

### 🎯 一句话定位

游戏 / 漫画 / 视觉小说画面在屏，按一下圆球，几秒后中文译文盖在原文上。

### 🖱️ 怎么触发翻译

- **悬浮球单击**：默认翻整个屏幕；可切到「划词模式」——按一下后用手指圈一小块文字单独翻（更准、专挑要看的那一段）
- **悬浮球长按**：弹出弧形子菜单，常用动作一指可达：循环翻译 / 重选区域 / 在「全屏翻译」和「划词翻译」之间切换 / 回主页。**按钮顺序可在设置里拖动重排**；超过一页自动出现「下一组」翻页按钮
- **循环模式**：自动每 2 秒翻一次；画面静止时自动跳过，看小说翻页都不用动手
- **音量双键**：同时按住「音量+ / 音量−」0.3 秒触发，手指不用离开游戏（需在系统无障碍中启用）
- **从其它 App 选中文字**：在任意 App 里长按选中一段文字 → 系统菜单选「屏译翻译」→ 直接弹译文卡片，省去切到屏译再框选的步骤

### 🔍 识别屏上的文字（OCR）

- **本机识别**：ML Kit（中日韩英）/ PaddleOCR——图不上传，无网也能用
- **云端识别**：百度 / 腾讯 / 有道——本机识不准时兜底
- 切源语言时会自动检查"当前 OCR 能不能认这门语言"，认不了会推荐换引擎，不用自己排查

### 🌐 翻译引擎

- **大语言模型**：DeepSeek / ChatGPT / 智谱 / 自架本地模型……一边翻一边显示，不用等整段
- **DeepL**：官方付费 / 免费版自动识别，**支持自架 [deeplx](https://github.com/OwO-Network/DeepLX)**（免 key 的开源代理，能在自己服务器上跑），可选「官方 / deeplx / 自动 fallback」三种协议
- **有道图翻**：截图直接换译文，跳过中间环节，特别适合漫画
- **Google**：免 key、免费（国内需代理）
- 每个引擎都有「测试连接」按钮，DeepL 顺带告诉你这个月还剩多少免费字数

### 🔤 划词翻译（按一个词出字典）

不只是翻整屏。框出屏上一个单词或短语，弹卡片一次告诉你：

- **译文** —— 所有翻译引擎都给
- **音标 / 词性 / 多条释义 / 例句对照** —— **使用大语言模型时**才有完整字典（百度 / 腾讯 / DeepL 这类只返回译文）
- 卡片上有「复制原文」「复制译文」两个按钮，长按文字也能复制
- 适合查游戏 / 漫画 / 视觉小说里冒出来的生词，单词长按一秒，比切回字典 App 快得多

### 🎨 译文怎么显示

- **两种渲染**：贴每段原文下 / 上 / 覆盖（BLOCKS），或装进一个可拖拽缩放的**悬浮窗口**（适合虚拟按键密集的游戏，让译文飘在画面上方不挡操作；可一键锁定避免误触）
- **5 套配色** + 字号、透明度、边框样式（实线 / 虚线 / 点线 / 双线 / 凹槽）都能调，预览实时跟随
- **漫画 / 字幕优化**：自动把被切成几段的同一句话合到一起再翻；竖排日漫从右往左拼；漢字旁的小注音（振假名）自动去掉，译文不再重复
- **长译文跑马灯**：单行模式下太长不会被省略号截断，自动横向滚动

### 🛠️ 用着舒服的小事

- **中英界面 + 浅 / 深 / 跟随系统主题**，立即切换不重启
- **设置项内搜索**：找不到选项？顶部搜一下，中英关键字都行
- **翻译结果缓存**：同样的句子不会重复扣你的 API 额度
- **崩溃自动留现场**：App 万一闪退 / 卡死，下次启动能在日志页一键导出问题报告
- **新版本自动提示**：进入主页自动检查（每天最多一次），国内拉不到会给你直链
- **国产手机后台引导**：小米 / OPPO / VIVO / 华为 / 三星 容易杀后台的手机，有一键跳转设置的引导
- **隐私保护**：明文 HTTP 默认只能在家里的局域网用，公网必须 HTTPS

## 📊 对比同类应用

> 仅作粗略参考，按 2026 年公开信息整理，可能与对方最新版本有差异。欢迎 PR 指正。

| 维度 | **屏译 (本)** | Gaminik / 爱译客（闭源同源） | Google 翻译 | 沉浸式翻译 |
|---|---|---|---|---|
| 完全免费 / 无广告 | ✅ | 订阅或内购 | ✅ | ✅ |
| 开源 | ✅ Apache 2.0 | ❌ | ❌ | 部分 |
| 屏幕悬浮球叠加翻译 | ✅ | ✅ | ❌ | ❌（移动端弱） |
| 端侧 OCR（图片不上传） | ✅ ML Kit + PaddleOCR | ❌（云端为主） | ✅ | ❌ |
| 多 OCR 引擎自选 | ✅ 5 端侧 + 3 云端 | 有限 | ❌ | ❌ |
| 多翻译引擎自选 | ✅ 7 家：LLM / DeepL / DeepLX / 有道 / Google / 火山 / 百度 / 腾讯 | 1–2 家固定 | ❌ | 翻译侧多 |
| 自架翻译后端（deeplx / 本地 LLM / 自定义 Bearer） | ✅ | ❌ | ❌ | ❌ |
| 悬浮窗口模式（可锁定防误触） | ✅ | ❌ | ❌ | ❌ |
| 漫画竖排 / 振假名 / 字幕段落合并 | ✅ | ❌ | ❌ | ❌ |
| 划词翻译卡片（音标 / 词性 / 释义 / 例句） | ✅ 使用大语言模型时完整 | ❓ | ⚠️ 仅 Google 翻译 App 内 | ⚠️ 移动端弱 |
| 在任意 App 长按选词直接翻译 | ✅ 系统选中文本菜单入口 | ❓ | ✅「点按翻译」 | ❌ |
| 悬浮球菜单按钮可自定义顺序与分页 | ✅ | ❌ | ❌ | ❌ |

**核心定位**：在「游戏 / 漫画屏幕翻译」赛道里，做唯一一个**开源 + 端侧引擎可选 + 自架翻译后端友好**的方案。差异化在「隐私 / 自托管 / 引擎自选」三个维度，而不是 OCR / 翻译质量本身（云端 OCR + DeepL/LLM 引擎大家都能用）。

## 📸 截图

**实际译文叠加** —— Discord 沟通规则页，OCR 紧贴原文渲染：

| 经典深色主题 | 浅色纸张主题 |
|---|---|
| <img src="docs/screenshots/overlay-discord-dark.png" width="320" alt="经典深色译文叠加" /> | <img src="docs/screenshots/overlay-discord-light.png" width="320" alt="浅色纸张译文叠加" /> |

**游戏场景** —— Sandship UI 的中文识别与覆盖：

<img src="docs/screenshots/overlay-game.png" width="640" alt="游戏内译文叠加" />

**漫画 / 字幕场景** —— 漫画气泡按列识别后译文紧贴覆盖：

| 韩文漫画 | 日文竖排漫画 |
|---|---|
| <img src="docs/screenshots/overlay-manga.png" width="320" alt="韩文漫画译文叠加" /> | <img src="docs/screenshots/overlay-manga-jp.png" width="320" alt="日文竖排漫画译文叠加" /> |

**设置页**：

| 应用语言 / 主题 / 翻译后端 | OCR 引擎 / 预处理 |
|---|---|
| <img src="docs/screenshots/settings-top.png" width="280" alt="设置顶部" /> | <img src="docs/screenshots/settings-ocr.png" width="280" alt="OCR 引擎设置" /> |
| **译文样式预览（BLOCKS 模式）** | **悬浮窗口模式选项** |
| <img src="docs/screenshots/settings-display.png" width="280" alt="译文显示设置（BLOCKS 模式）" /> | <img src="docs/screenshots/settings-display-floating.png" width="280" alt="悬浮窗口模式设置" /> |
| **段落合并 / 悬浮球** | |
| <img src="docs/screenshots/settings-floating.png" width="280" alt="段落合并与悬浮球设置" /> | |

**悬浮窗口模式** —— 把所有原文 / 译文集中到一个可拖拽缩放的浮窗里，避免「贴原文」模式下译文挡住游戏操控（虚拟摇杆 / 按键 / 对话推进键）。窗口支持**锁定**：解锁时可拖动、可缩放、带 X 关闭按钮和底部缩放手柄；锁定后只剩文字内容，不响应任何手势，彻底防误触。

| 解锁（可拖拽 / 缩放 / 关闭） | 锁定（防误触，只剩内容） |
|---|---|
| <img src="docs/screenshots/floating-window-unlocked.jpg" width="420" alt="悬浮窗口解锁状态" /> | <img src="docs/screenshots/floating-window-locked.jpg" width="420" alt="悬浮窗口锁定状态" /> |

**长按悬浮球的弧形子菜单 & 划词翻译卡片** —— 左：长按弹出菜单，循环 / 选区 / 在「全屏翻译」和「划词翻译」之间切换 / 回主页（按钮顺序可在设置里拖动重排）；右：圈出屏上一个词后弹出的卡片，连读音、词性、多条释义、例句一起呈现（使用大语言模型时才能拿到这套完整字典）。

| 长按弹出的弧形菜单 | 划词翻译卡片（带字典） |
|---|---|
| <img src="docs/screenshots/arc-menu.png" width="320" alt="长按悬浮球弹出弧形菜单" /> | <img src="docs/screenshots/word-select.png" width="320" alt="划词翻译字典卡片" /> |

## 📦 安装

1. 到本仓库的 [Releases](../../releases) 页下载最新 `ScreenTranslator-x.y.z.apk`
2. 在 Android 设备上点击安装（首次需在系统设置允许"安装未知来源应用"）
3. 启动后依次授予 **悬浮窗**、**通知** 权限

只发布 **`arm64-v8a`（64 位 ARM）** 架构。armeabi-v7a / x86 **暂不支持**——端侧 OCR（PaddleOCR / ML Kit）的 native lib 体积大；且 32 位 ARM 缺 64 位 NEON 优化、可用寄存器少，推理慢一档，OCR 等待时间会明显拉长。如有 32 位设备需求请在 Issue 反馈。

每个 APK 同时附带 `.sha256` 校验文件，可对照本地 `Get-FileHash` / `sha256sum` 输出确认下载完整性。

## 🚀 使用

1. 启动 App，点 **启动截屏服务**，确认系统弹出的"开始截屏？"对话框
2. 切到任意游戏 / 视觉小说 / 漫画 App
3. 点屏幕上的圆形悬浮按钮 → 2~3 秒内底部出现译文
4. 长按悬浮按钮 → 切换循环模式（默认 2 秒一次，外圈进度环匀速转一圈对应一次截屏，dHash 跳过静止画面）
5. 单击译文条 → 隐藏

可选：
- 在系统"无障碍"里启用本应用，**同时按下音量加 / 音量减并按住 300ms** 作为全局触发，免去手指点屏（不读屏 / 不解析 View 树）
- 安装 [Shizuku](https://github.com/RikkaApps/Shizuku) 并授权后，在设置切换到 Shizuku 截屏路径，免去每次的系统授权弹窗

## ⚙️ 配置

启动 App 后进入"设置"。设置页顶部可切换 **应用语言** 与 **主题模式**；任意 section 都可用搜索图标按关键字（中英文都行）快速跳转。

### OCR 引擎

| 引擎 | 适用场景 | 备注 |
|---|---|---|
| **端侧** ML Kit (auto / latin / ja / zh / ko) | 默认；日文 / 中文 / 韩文 / 拉丁字符 | 无需外网，端侧推理 |
| **端侧** PaddleOCR PP-OCRv5 mobile | 多语种密排文字、UI 按钮 | 首次使用需下载 ONNX 模型，见下 |
| **云端** 百度 OCR | ML Kit / Paddle 漏检兜底 | 需 API Key + Secret，按量计费；5 个 endpoint（标准/含位置/高精度/含位置高精度/网络图片）；图片有尺寸 / 宽高比限制 |
| **云端** 腾讯 OCR | 同上 | 需 SecretId + SecretKey；3 个 endpoint：GeneralBasic / GeneralAccurate / **RecognizeAgent**（LLM 智能体，已对接 ParagNo 段落分组合并） |
| **云端** 有道云 OCR | 简单一键 | 需 应用 ID（API Key）+ 应用密钥；langType 跟随源语言自动映射，无需独立选择 |

**PaddleOCR 模型下载**：设置 → "下载 PaddleOCR 模型"，自动从 HuggingFace / hf-mirror 镜像拉取以下三个文件到 `<filesDir>/models/paddle/`：

- `det.onnx`（DBNet 检测，约 4.5 MB）
- `rec.onnx`（CRNN 识别，约 15.7 MB）
- `keys.txt`（v5 字典，约 90 KB）

可在设置里自定义镜像 URL，或手动从本地文件导入。

**百度 OCR 注意事项**：图片限制为 *最长边 ≤ 4096px、最短边 ≥ 15px、宽高比 1:4 ~ 4:1、base64 后 < 4MB*。本项目对前两条会自动缩放兜底，宽高比超限只能调小 / 重画截屏区。

### 翻译引擎

**OpenAI 兼容**：

- **Base URL**：以 `/v1/` 结尾，例如：
  - SiliconFlow `https://api.siliconflow.cn/v1/`
  - OpenAI 官方 `https://api.openai.com/v1/`
  - 智谱 BigModel `https://open.bigmodel.cn/api/paas/v4/`
  - 自架 Ollama `http://<host>:11434/v1/`
- **API Key**：对应平台的 sk-xxx
- **模型名**（示例）：
  - SiliconFlow: `Qwen/Qwen2.5-7B-Instruct`、`Qwen/Qwen2.5-14B-Instruct`
  - OpenAI: `gpt-4o-mini`、`gpt-4o`
  - 智谱: `glm-4-flash`、`glm-4-plus`
  - Ollama: `qwen2.5:7b`、`llama3.1:8b`
- **Prompt 模板**：默认 galgame 口语化风格，可自定义。Prompt 跟随 UI 语言：从未改过 prompt 的用户切换 UI 语言时自动迁移到对应 locale 的默认 prompt；已自定义的 prompt 不会被覆盖。

**DeepL**：填 Auth Key（free 版 key 末尾带 `:fx`），自动选择 free / pro endpoint。点 **测试连接** 顺带返回当月已用 / 总字符额度。

**有道图翻**（端到端引擎）：填有道智云一组 应用 ID + 应用密钥（OCR / 图翻共用）。选这个后会 **绕过上方 OCR 引擎设置**，CaptureService 把整张截图发到 `ocrtransapi` 直接拿带 box 的译文。会按图片 orientation 自动反旋转 box 坐标修正位置。**测试连接** 发 2×2 像素小图按 errorCode 精准判 key / 服务 / 限流。

**Google**（非官方端点）：无需 key、免费。**国内必须代理**；谷歌可能随时限流 / 改端点，仅供学习。

**火山引擎翻译**（字节家的）：在火山控制台开通"机器翻译"，把生成的两把钥匙（AK / SK）填进来。地区保持默认就行。优点是循环模式下不容易被限速。

**百度翻译**（注意：**和上面 OCR 那个"百度"不是同一个账号**，要去百度翻译开放平台另外注册）：填 APPID 和密钥两栏。个人免费额度大概一个月 5 万字，够每天看几篇视觉小说用。

**腾讯翻译**：和上面 OCR 的腾讯共用一组密钥——如果你已经在 OCR 那边填过腾讯的 SecretId / SecretKey，这里完全不用再填。

### 显示

- **渲染模式**：BLOCKS（按 OCR 框紧贴原文）/ **悬浮窗口**（可拖拽缩放的独立浮窗，详见上方[截图章节](#-截图)）
- **位置**：BLOCKS 下可选 below / overlap / above + 像素级 x/y 偏移微调
- **悬浮窗口**：可切「原文+译文 / 仅译文」两种内容形态；**锁定**后窗口不可拖拽 / 不可缩放，适合游戏中防误触；底部「重置位置 / 大小」按钮一键回到默认
- **主题色**：5 种预设 + 自定义（背景 / 文字 / 边框 ARGB / 边框样式实线/虚线/点线/双线/凹槽）
- **避让 & 合并**：碰撞检测限制译文宽度不挤进相邻 OCR 框；OCR 后合并相邻 box 提供 **保守 / 标准 / 激进** 三档强度——保守适合视觉小说 / 长段密集，标准适合多数场景，激进适合漫画气泡内多列被切碎的情形（可能误合相邻气泡）

## ⚠️ 已知限制

- Android 14+ 每次首次启动截屏都会弹一次系统授权窗，这是 Google 设计；Shizuku 路径可绕过
- 部分 ROM（小米 / OPPO / VIVO）默认杀后台 / 拦截悬浮窗，需手动加入电池白名单 + 允许后台启动；应用内有兼容引导
- 反作弊网游可能把 MediaProjection 判为外挂截屏 → 本项目仅适用于单机 / 视觉小说 / 漫画
- 设置了 `FLAG_SECURE` 的画面（部分网银 / 视频 App）截出来是黑屏，本项目不做绕过
- PaddleOCR 端侧推理在低端机（骁龙 7 系以下）单次约 1~3 秒；推荐配合区域选择使用

## 🗺️ 路线图

### ✅ 已完成

- [x] **能用起来**（M0）：截屏 → 识字 → 翻译 → 屏上显示，一条龙跑通
- [x] **顺手好用**（M1）：记住截屏区域 / 边翻边显示 / 译文紧贴原文 / 国产手机后台引导 / 中英界面 + 浅深主题 / 设置内搜索
- [x] **更聪明更稳**（M2）：韩文识字、音量双键全局触发、换源语言时推荐能识它的 OCR、段落合并三档强度、循环进度可视化、闪退自动留报告、新版本自动提醒
- [x] **引擎大扩张**（M3 · 0.3.x）：每个翻译引擎都有「测试连接」按钮、加入有道云 OCR + 有道图翻、Google 翻译、腾讯智能体段落合并、漫画里的小注音不再重复翻、长译文跑马灯滚动
- [x] **从整屏到单词**（M4 · 0.3.4+）：划词翻译卡片（音标 / 词性 / 释义 / 例句）、主球单击行为可切换（整屏翻 ↔ 划词翻）、弧菜单按钮顺序可自定义、其它 App 长按选词进入屏译翻译

### 📋 计划中

- [ ] **译文配色跟着画面走**：智能取色 + 自动调字号，替代纯色矩形条，看起来更像「印在画面里」而不是贴上去的标签
- [ ] **漫画专用识字**：接入 manga-ocr 模型，竖排日漫识得更准
- [ ] **译文字体自选**：让你上传 .ttf 换个心仪的中文字体
- [ ] **Shizuku 截屏提速到循环模式可用**：当前 Shizuku 通路虽然能免每次系统授权弹窗，但 ~5 FPS 上限只适合按需触发；要升级到能跑循环翻译的稳定通路
- [ ] **翻译历史**：能回看之前几屏的译文，找回上一段对话
- [ ] **译文朗读（TTS）**：边玩边听译文读出来
- [ ] **专有名词词表**：游戏 / 漫画里的人名、道具锁定固定译法，不再每次翻得不一样
- [ ] **离线词典兜底**：不联网时也能给百度 / 腾讯 / DeepL 这类无字典 API 的引擎补上释义

## 🤝 参与开发

欢迎社区贡献。无论是 bug 修复、新功能、UI 改进、翻译还是文档勘误，都很受欢迎。

### 分支与 PR 规则

> ⚠️ **不要直接 PR 到 `main` 分支。**
>
> `main` 是稳定主线，**只接受经维护者审核合入** —— 用作打 tag 发版的基线。
>
> 外部 PR 请按以下流程走：

1. **先开 issue 讨论**：说明你想做的改动 / 修的 bug，确认方向，避免做白工
2. **Fork 仓库**，从最新 `main` 切出 feature 分支：
   ```bash
   git checkout -b feat/your-idea  origin/main
   ```
3. **本地开发并自测**（至少在一台真机上跑通 `./gradlew installDebug`）
4. **PR 提交到 `dev` 分支**（如仓库还没有 `dev`，请在 issue 里 ping 维护者建立）；维护者会在 `dev` 上聚合多人 PR、复测后整合到 `main`
5. **直接 push 到 `main` / 强推 `main` 是被禁止的**（仓库会通过 branch protection 拦截）

### 本地构建

```bash
git clone https://github.com/ciddwd/overlay-translator.git
cd overlay-translator
cp local.properties.example local.properties
# 编辑 local.properties，把 sdk.dir 改成本机 Android SDK 路径
./gradlew installDebug   # 装到已连接设备
```

需 JDK 17 + Android SDK 35。Android Studio 打开会自动同步并补 Gradle wrapper jar；命令行环境需先 `gradle wrapper --gradle-version 8.10.2`。

### 翻译贡献

如果想加入新语言或修正现有翻译：

1. 复制 `app/src/main/res/values/strings.xml` 到 `app/src/main/res/values-<lang>/strings.xml`（例如 `values-zh-rTW`、`values-ja`）
2. 只翻译 `<string>` 标签内的值，**保留** key（`name="..."`）和占位符（`{source}`、`{target}`、`%1$s`、`\n` 等）
3. 在 `app/src/main/res/xml/locales_config.xml` 添加 `<locale android:name="<lang>" />`
4. 在 `app/src/main/java/com/gameocr/app/ui/SettingsScreen.kt` 的 `APP_LANGUAGE_OPTIONS` 追加一行
5. 在 PR 描述里附上覆盖率（多少条字符串已翻译）与试译截图

未来若有 10+ 种语言，会迁移到 [Weblate](https://weblate.org/) 在线协作平台，简化流程。

### Commit message

推荐遵循 [Conventional Commits](https://www.conventionalcommits.org/) 简化版：

```
feat: 新功能
fix:  bug 修复
docs: 文档
refactor: 重构（不改行为）
chore: 构建 / CI / 工具链
i18n: 翻译相关
```

副标题与正文请用中文或英文，**不要混在一句话里**；首行 ≤ 72 字符。

### 行为准则

- 友善、就事论事，对人不动情绪
- 不发与项目无关的内容 / 商业推广 / 政治议题
- 维护者保留以"不符合项目方向"为由关闭 issue / PR 的权利，并会说明原因

## 🙏 致谢

### 上游项目

- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) · PP-OCRv5 mobile 模型
- [bukuroo/PPOCRv5-ONNX](https://huggingface.co/bukuroo/PPOCRv5-ONNX) · 已转 ONNX 的 v5 mobile 镜像
- [Shizuku](https://github.com/RikkaApps/Shizuku) · 免 ROOT 的高权限通道
- [ML Kit](https://developers.google.com/ml-kit) · Google 端侧 OCR
- [ONNX Runtime](https://onnxruntime.ai/) · 端侧推理引擎
- [Jetpack Compose](https://developer.android.com/jetpack/compose) / [Material 3](https://m3.material.io/) · UI 体系
- [Hilt](https://dagger.dev/hilt/) · 依赖注入
- [Retrofit](https://square.github.io/retrofit/) + [OkHttp](https://square.github.io/okhttp/) · 网络
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) · JSON 序列化
- [Timber](https://github.com/JakeWharton/timber) · 日志

### 贡献者

<a href="https://github.com/ciddwd/overlay-translator/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=ciddwd/overlay-translator" />
</a>

（首次 PR 合入后会自动出现在这里）

### 灵感来源

桌面端 VNR / Visual Novel Reader 等同类工具长期以来在 PC 平台为 galgame / 视觉小说玩家提供实时翻译。本项目把这条管线带到 Android，并针对手机场景（电池、ROM 限制、touch UI）做了重新设计。

## 📄 许可

代码采用 [Apache-2.0](LICENSE)。模型与第三方依赖各自保留原协议。

