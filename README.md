# CapsWriter 语音输入法（安卓）

<div align="center">

基于飞牛 NAS 上 **CapsWriter Offline** 服务端的安卓语音 + 拼音输入法（IME）。

语音识别完全在 NAS 服务端完成（本机零模型）；拼音 / 剪贴板等键盘功能在本机实现。

</div>

## ✨ 核心特性

- **语音听写**：长按/短按麦克风说话，识别文本实时回显并上屏。
  - 长按麦克风：按住说话，松手即识别；按住时**上滑**再松手可取消。
  - 短按麦克风：进入连续录音，再点一下结束（最长 3 分钟兜底自动停止）。
  - 本地 VAD 静音检测：连续静音自动收尾，减少无效上传。
- **拼音键盘（26 键）**：QWERTY 布局，支持**全拼 / 自然码双拼 / 英文**三种输入。
  - **不完整拼音补全**：输入 `ni m` 或 `nim` 自动补全为 `ni + men` 召回「你们」。
  - **词库约束分词**：`xuni` 正确切分为 `xu + ni`（虚拟）而非 `xun + i`（寻）。
  - 候选栏智能预测、中英一键切换、双击+长按删除键清空全部。
- **剪贴板历史**（输入法内嵌面板）：复制内容自动保存（AES-256-GCM 加密入库），
  支持分类（全部/网址/隐私/数字/收藏）、动态序号、点击即粘贴、长按收藏/隐私/删除。
  - 隐私内容默认只显示前 3 字符，点击揭示后再粘贴。
  - 剪贴板内容 AES-256-GCM 加密保存在本机私有数据库，仅本输入法可读取。
- **后台保活 / 防杀后台**：前台常驻服务 + 无障碍互保 + 开机自启；可选 Root/Shizuku 白名单。
- **零额外模型**：不打包任何语音模型，APK 约 10MB，仅依赖 `core-ktx`、`activity-ktx`、`okhttp3`。

## 🏗️ 架构

```
麦克风 → MicRecorder(16kHz PCM16) → AsrClient(WebSocket) → 飞牛 NAS CapsWriter Offline
                                                                        ↓
拼音键盘 / 剪贴板面板（本机） ← 识别文本（整段累积，整体覆盖显示）
```

| 模块 | 职责 |
|---|---|
| `JinnIme` | 输入法服务：语音/拼音双模式、手势、结果回显、保活拉起 |
| `PinyinEngine` | 拼音引擎：词库加载、候选查询、自然码双拼、不完整拼音补全、词库约束分词 |
| `PinyinKeyboardView` | 26 键键盘 + 候选栏 + 功能面板（剪贴板/方向/粘贴/收起） |
| `ClipboardPanelView` | 剪贴板内嵌面板（分类/搜索/收藏/隐私/清空） |
| `ClipboardDb` | 剪贴板历史 SQLite（AES-256-GCM 加密） |
| `AsrClient` | WebSocket 客户端：音频流式上传、断线指数退避重连 |
| `Diagnostics` | 诊断日志：文件输出 + 崩溃捕获 + Trace ID + 事件序列 |

## 🔌 对接服务端

服务端为飞牛 NAS 上的 CapsWriter Offline（Docker），端口 `6016`。

| 项 | 值 |
| --- | --- |
| 地址 | 飞牛 NAS 局域网地址，如 `192.168.1.3` |
| 端口 | `6016` |
| 协议 | `ws://<host>:6016`，子协议 `binary` |
| 音频格式 | 16kHz / 单声道 / float32 小端裸样本（Base64） |
| 分段参数 | `seg_duration=60`、`seg_overlap=4` |

通信严格对齐服务端 `core/protocol.py`：一次听写由若干 `is_final=false` 音频包 + 一个 `data=""` 的 `is_final=true` 收尾包组成；
服务端返回**整段累积文本**，客户端整体覆盖显示，绝不自行拼接。

## 🛠️ 构建

### 环境
- JDK 17、Android SDK（compileSdk 34 / minSdk 26）
- Gradle 8.9（项目内置 wrapper）

### 一键构建（推荐）

```bash
python build_apk.py              # 编译 release，产物 jinn-release.apk 到项目根目录
python build_apk.py --install    # 编译并安装到已连接设备
python build_apk.py --clean      # clean 后全新编译
```

### 手动构建

```bash
./gradlew assembleDebug     # Debug（未签名）
./gradlew assembleRelease   # Release（本地配置签名后自动签名）
```

### 签名说明

开源仓库**不包含签名密钥与密码**。如需签名构建：

1. 生成密钥库：`keytool -genkeypair -keystore keystore/jinn-release.jks -alias jinn ...`
2. 项目根目录创建 `keystore.properties`（已被 .gitignore 忽略）：
   ```properties
   storeFile=keystore/jinn-release.jks
   storePassword=***
   keyAlias=jinn
   keyPassword=***
   ```
3. `./gradlew assembleRelease` 将自动签名。

## 📚 词库

内置词库（`app/src/main/assets/`）：
- `pinyin_phrases.txt`：约 105 万键（拼音串 → 词语，按词频降序）
- `pinyin_chars.txt`：416 音节（音节 → 单字）
- `pinyin_syllables.txt`：合法音节全集

数据来源与许可证详见 [docs/DATA_SOURCES.md](docs/DATA_SOURCES.md)。
词库构建工具见 `tools/dict_builder/`（Rime 词库 → 项目格式；THUOCL 自动注音扩充）。

## 📄 许可证

本项目采用 **GNU GPL v3.0**（见 [LICENSE](LICENSE)）。

> ⚠️ 内置词库含 GPL-3.0 来源（雾凇/白霜拼音），按 GPL 传染性要求，本项目整体以 GPL-3.0 分发。
> 第三方数据源（THUOCL/MIT、pypinyin/MIT）兼容此许可。

## 🙏 致谢

- [CapsWriter Offline](https://github.com/HaujetZhao/CapsWriter-Offline) — 语音识别服务端
- [iDvel/rime-ice（雾凇拼音）](https://github.com/iDvel/rime-ice) — 词库
- [thunlp/THUOCL](https://github.com/thunlp/THUOCL) — 清华开放中文词库
- [mozillazg/pypinyin](https://github.com/mozillazg/pypinyin) — 注音工具
