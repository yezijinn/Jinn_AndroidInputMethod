# CapsWriter 语音输入法（安卓） / CapsWriter Voice IME (Android)

<div align="center">

基于飞牛 NAS 上 **CapsWriter Offline** 服务端的安卓语音 + 拼音输入法（IME）。

An Android voice + pinyin input method (IME) powered by **CapsWriter Offline** on FeiNiu NAS.

语音识别完全在 NAS 服务端完成（本机零模型）；拼音 / 剪贴板等键盘功能在本机实现。

Speech recognition runs entirely on the NAS server (zero on-device models); pinyin, clipboard and other keyboard features are implemented locally.

</div>

## ✨ 核心特性 / Features

- **语音听写 / Voice dictation**：长按/短按麦克风说话，识别文本实时回显并上屏。
  Press-and-hold or tap the mic to speak; recognized text streams back and is committed.
  - 长按麦克风：按住说话，松手即识别；按住时**上滑**再松手可取消。
    Hold to talk, release to recognize; swipe **up** while holding to cancel.
  - 短按麦克风：进入连续录音，再点一下结束（最长 3 分钟兜底自动停止）。
    Tap once for continuous recording, tap again to stop (auto-stop after 3 min).
  - 本地 VAD 静音检测：连续静音自动收尾，减少无效上传。
    Local VAD silence detection trims idle audio and reduces useless uploads.
- **拼音键盘（26 键）/ Pinyin keyboard (QWERTY)**：支持**全拼 / 自然码双拼 / 英文**三种输入。
  Full pinyin, Ziranma Shuangpin, and English modes.
  - **不完整拼音补全 / Incomplete-pinyin completion**：`ni m` 或 `nim` 自动补全为 `ni + men` 召回「你们」。
  - **词库约束分词 / Dictionary-constrained segmentation**：`xuni` 正确切分为 `xu + ni`（虚拟）而非 `xun + i`（寻）。
  - 候选栏智能预测、中英一键切换、双击+长按删除键清空全部。
    Candidate prediction, one-tap CN/EN switch, double-tap + long-press backspace to clear all.
- **剪贴板历史 / Clipboard history**（输入法内嵌面板）：复制内容自动保存（AES-256-GCM 加密入库），
  支持分类（全部/网址/数字/收藏）、动态序号、点击即粘贴、长按收藏/删除、清空二次确认。
  Auto-saves copies (AES-256-GCM encrypted), with categories (All/URL/Number/Favorites), dynamic numbering, tap-to-paste, long-press favorite/delete, and clear confirmation.
  - **顶部搜索面板 / Top search panel**：候选栏上方展示搜索面板，输入关键词即可实时检索历史，
    结果可滚动、点击直接上屏，退出搜索即恢复正常 26 键。
    A search panel above the candidate bar filters history in real time; results scroll and paste on tap; exit restores the normal keyboard.
  - 剪贴板内容 AES-256-GCM 加密保存在本机私有数据库，仅本输入法可读取。
    Entries are AES-256-GCM encrypted in a private local DB, readable only by this IME.
- **后台保活 / Keep-alive**：前台常驻服务 + 无障碍互保 + 开机自启；可选 Root/Shizuku 白名单。
  Foreground service + accessibility mutual keep-alive + boot auto-start; optional Root/Shizuku whitelist.
- **零额外模型 / Zero bundled models**：不打包任何语音模型，APK 约 10MB，仅依赖 `core-ktx`、`activity-ktx`、`okhttp3`。

## 🏗️ 架构 / Architecture

```
麦克风 → MicRecorder(16kHz PCM16) → AsrClient(WebSocket) → 飞牛 NAS CapsWriter Offline
Mic     →  MicRecorder (16kHz PCM16)  → AsrClient (WebSocket)  → FeiNiu NAS CapsWriter Offline
                                                                        ↓
拼音键盘 / 剪贴板面板（本机） ← 识别文本（整段累积，整体覆盖显示）
Pinyin keyboard / clipboard panels (local)  ← recognized text (accumulated, overwrite-displayed)
```

| 模块 / Module | 职责 / Responsibility |
|---|---|
| `JinnIme` | 输入法服务：语音/拼音双模式、手势、结果回显、保活拉起 / IME service: voice+pinyin modes, gestures, echo, keep-alive |
| `PinyinEngine` | 拼音引擎：词库加载、候选查询、双拼、不完整补全、词库约束分词 / Pinyin engine: lexicon, candidates, shuangpin, completion, segmentation |
| `PinyinKeyboardView` | 26 键键盘 + 候选栏 + 功能面板 / 26-key keyboard + candidate bar + function panels |
| `ClipboardPanelView` | 剪贴板内嵌面板（分类/粘贴/收藏/删除/清空） / Embedded clipboard panel (categories/paste/favorite/delete/clear) |
| `SearchPanelView` | 顶部搜索面板：结果列表 + 搜索框 + 退出搜索 / Top search panel: results + input + exit |
| `ClipboardDb` | 剪贴板历史 SQLite（AES-256-GCM 加密） / Clipboard history SQLite (AES-256-GCM) |
| `AsrClient` | WebSocket 客户端：音频流式上传、断线指数退避重连 / WebSocket client: streaming upload, exp-backoff reconnect |
| `Diagnostics` | 诊断日志：文件输出 + 崩溃捕获 + Trace ID / Diagnostic logs, crash capture, Trace ID |

## 🔌 对接服务端 / Server Setup

服务端为飞牛 NAS 上的 CapsWriter Offline（Docker），端口 `6016`。
The server is CapsWriter Offline (Docker) on the FeiNiu NAS, port `6016`.

| 项 / Item | 值 / Value |
| --- | --- |
| 地址 / Host | 飞牛 NAS 局域网地址，如 / e.g. `192.168.1.3` |
| 端口 / Port | `6016` |
| 协议 / Protocol | `ws://<host>:6016`，子协议 `binary` |
| 音频格式 / Audio | 16kHz / 单声道 / float32 小端裸样本（Base64） / mono float32-le raw samples (Base64) |
| 分段参数 / Segmentation | `seg_duration=60`、`seg_overlap=4` |

通信严格对齐服务端 `core/protocol.py`：一次听写由若干 `is_final=false` 音频包 + 一个 `data=""` 的 `is_final=true` 收尾包组成；
服务端返回**整段累积文本**，客户端整体覆盖显示，绝不自行拼接。
The protocol strictly follows `core/protocol.py`: one dictation = several `is_final=false` audio frames + a trailing `is_final=true` frame with `data=""`; the server returns **cumulative text** which the client overwrite-displays, never concatenating itself.

## 🛠️ 构建 / Build

### 环境 / Requirements
- JDK 17、Android SDK（compileSdk 34 / minSdk 26）/ JDK 17, Android SDK (compileSdk 34 / minSdk 26)
- Gradle 8.9（项目内置 wrapper）/ Gradle 8.9 (bundled wrapper)

### 一键构建 / One-click build（推荐）
```bash
python build_apk.py              # 编译 release，产物 jinn-release.apk 到项目根目录 / builds release to ./jinn-release.apk
python build_apk.py --install    # 编译并安装到已连接设备 / build & install to connected device
python build_apk.py --clean      # clean 后全新编译 / clean build
```

### 手动构建 / Manual build
```bash
./gradlew assembleDebug     # Debug（未签名）/ unsigned
./gradlew assembleRelease   # Release（本地配置签名后自动签名）/ auto-signed with local keystore
```

### 签名说明 / Signing
开源仓库**不包含签名密钥与密码**。如需签名构建：
The repo does **not** ship signing keys. To sign locally:
1. 生成密钥库 / create keystore: `keytool -genkeypair -keystore keystore/jinn-release.jks -alias jinn ...`
2. 项目根目录创建 / create `keystore.properties`（已被 .gitignore 忽略 / git-ignored）：
   ```properties
   storeFile=keystore/jinn-release.jks
   storePassword=***
   keyAlias=jinn
   keyPassword=***
   ```
3. `./gradlew assembleRelease` 将自动签名 / signs automatically.

## 📚 词库 / Dictionaries

内置词库 / Built-in assets（`app/src/main/assets/`）：
- `pinyin_phrases.txt`：约 105 万键（拼音串 → 词语，按词频降序）/ ~1.05M entries (pinyin → phrase, freq-desc)
- `pinyin_chars.txt`：416 音节（音节 → 单字）/ 416 syllables (syllable → char)
- `pinyin_syllables.txt`：合法音节全集 / full valid-syllable set

数据来源与许可证详见 / Sources & licenses: [docs/DATA_SOURCES.md](docs/DATA_SOURCES.md)。
词库构建工具见 / Dictionary builder: `tools/dict_builder/`（Rime → 项目格式；THUOCL 自动注音）。

## 📄 许可证 / License

本项目采用 **GNU GPL v3.0**（见 / see [LICENSE](LICENSE)）。

> ⚠️ 内置词库含 GPL-3.0 来源（雾凇/白霜拼音），按 GPL 传染性要求，本项目整体以 GPL-3.0 分发。
> The bundled lexicon contains GPL-3.0 sources (rime-ice/bai-shuang); under GPL copyleft the project is distributed as GPL-3.0.
> 第三方数据源（THUOCL/MIT、pypinyin/MIT）兼容此许可。 / Third-party sources (THUOCL/MIT, pypinyin/MIT) are compatible.

## 🙏 致谢 / Credits

- [CapsWriter Offline](https://github.com/HaujetZhao/CapsWriter-Offline) — 语音识别服务端 / ASR server
- [iDvel/rime-ice（雾凇拼音）](https://github.com/iDvel/rime-ice) — 词库 / lexicon
- [thunlp/THUOCL](https://github.com/thunlp/THUOCL) — 清华开放中文词库 / Tsinghua open Chinese lexicon
- [mozillazg/pypinyin](https://github.com/mozillazg/pypinyin) — 注音工具 / pinyin tool
