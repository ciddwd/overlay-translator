# 贡献指南

感谢愿意为 **屏译（Screen Translator）** 出力。无论是修 bug、加功能、改文档还是补翻译都很欢迎。

## 在动手前

1. 先到 [Issues](../../issues) 搜一下有没有人在做同样的事
2. 没有就开一个 issue 说明你的想法，**等待维护者回应**再开始编码 —— 避免做白工 / 方向不一致

## 工作流

```
       feature 分支            dev          main (受保护)
       (你的 fork)
            │                   │             │
   你 ──► 提 PR ──────────────► │             │
                                │             │
                              维护者         维护者
                              聚合 / 复测 ──► merge
                                              │
                                            打 tag
                                              │
                                          GitHub Release
```

### 详细步骤

1. **Fork** 本仓库到自己的账号
2. 从最新 `main` 切出 feature 分支：
   ```bash
   git checkout -b feat/your-idea origin/main
   ```
   分支名建议加前缀：`feat/...`、`fix/...`、`docs/...`、`i18n/...`、`refactor/...`
3. 本地开发 + 自测
4. push 到你 fork 的远端：
   ```bash
   git push origin feat/your-idea
   ```
5. 在 GitHub UI 上发起 PR，**目标分支选 `dev`**

> ⚠️ **不要把 PR 提到 `main`**。`main` 是发版基线，受 branch protection 保护，不接受外部直接 PR。
> 如果不小心选错了，维护者会要求改 base 到 `dev`。

### 维护者的合入流程

- 维护者把多个 PR 在 `dev` 上聚合、复测
- 通过 CI、人工 review 后，由维护者把 `dev` merge 到 `main`
- `main` 上打 tag → CI 自动发版

## 自测清单

提 PR 前请至少确认：

- [ ] `./gradlew assembleDebug` 通过（项目根目录）
- [ ] 至少在一台真机 / 模拟器上跑过 `./gradlew installDebug`，主要路径（启动截屏 → OCR → 翻译 → 叠加显示）能跑通
- [ ] 若是 UI / 主题 / i18n 相关，浅色 + 深色 + 中 + 英都过一下
- [ ] 没有引入未使用的 import / dead code
- [ ] 没有把 `.env` / `release.jks` / API Key 误提交

## Commit message

遵循 [Conventional Commits](https://www.conventionalcommits.org/) 简化版：

| 前缀 | 含义 |
|---|---|
| `feat:` | 新功能 |
| `fix:` | bug 修复 |
| `docs:` | 文档 |
| `refactor:` | 重构（不改行为） |
| `perf:` | 性能优化 |
| `style:` | 代码风格 / 格式 |
| `test:` | 测试相关 |
| `chore:` | 构建 / CI / 工具链 |
| `i18n:` | 翻译 / 本地化 |

格式：

```
<type>: <subject under 72 chars>

<可选正文，解释 why 而不是 what>

<可选 footer，关联 issue：Closes #123>
```

**中文 / 英文都行**，但同一个 subject 行不要混用。

## 代码规范

- Kotlin：遵循 [Kotlin 官方代码风格](https://kotlinlang.org/docs/coding-conventions.html)
- 已经有的注释规范：注释 WHY，不注释 WHAT；不要写多段 docstring，一行一句话足够
- UI 文案 **不要硬编码中文 / 英文** 在 .kt 文件里，写到 `res/values/strings.xml` 和 `res/values-en/strings.xml` 用 `stringResource(R.string.xxx)` 引用
- 新增的 `*.kt` 文件，包名跟目录对齐：`com.gameocr.app.<module>`

## 翻译贡献

加新语言或修翻译：

1. 复制 `app/src/main/res/values/strings.xml` 到 `app/src/main/res/values-<lang>/strings.xml`
   - 例：繁中 → `values-zh-rTW`、日文 → `values-ja`、韩文 → `values-ko`、俄文 → `values-ru`
2. 只翻译 `<string>` 标签内的 **值**，**保留**：
   - key（`name="..."`）
   - 占位符（`{source}`、`{target}`、`%1$s`、`%2$d` 等）
   - 转义符（`\n`、`\"`、HTML 实体 `&lt;` `&amp;` 等）
3. 在 `app/src/main/res/xml/locales_config.xml` 加 `<locale android:name="<lang>" />`
4. 在 `app/src/main/java/com/gameocr/app/ui/SettingsScreen.kt` 的 `APP_LANGUAGE_OPTIONS` 列表追加一行，并在 `values*/strings.xml` 里加对应 chip label：`settings_app_lang_<lang>`
5. PR 描述里附：覆盖率（多少 / 共多少条已翻）+ 试译截图（设置页 + 主页）

未来若有 10+ 种语言会迁移到 [Weblate](https://weblate.org/) 简化协作。

## 行为准则

- 友善、就事论事，对人不动情绪
- 不发与项目无关的内容 / 商业推广 / 政治议题
- 维护者保留以"不符合项目方向"为由关闭 issue / PR 的权利，会简要说明原因
- 不接受充满情绪的 PR 评论；技术争议请用论据，不用感叹号

## License 同意

提交 PR 即视为同意你的贡献以 [Apache-2.0](LICENSE) 协议被并入本项目。
