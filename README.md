# Jinn 拼音输入法

<div align="center">

一个轻量的安卓拼音输入法（IME）：26 键全拼 / 双拼（7 套方案）、剪贴板历史、分类词库。

全部功能在本机运行，不需要任何服务端。

[English](README_EN.md) ｜ 中文

</div>

> ### 关于「语音听写」的说明
>
> 语音听写是可选的，不开箱即用：识别完全在服务端完成，Android 端不打包任何模型。
> 服务端需要你自己在一台家用 NAS 上部署 [CapsWriter Offline](https://github.com/HaujetZhao/CapsWriter-Offline)
> （Docker 镜像），Android 端再通过 WebSocket 把音频流上传给它。
>
> **没有可访问的自建 NAS 服务端，语音功能就用不了**，它面向已经搭好该服务端的人，不适合普通用户。
>
> 拼音键盘、剪贴板、分类词库等核心功能全部在本机实现，与语音无关，无需任何服务端。

## 核心特性

- **拼音键盘（26 键）**：全拼 / 双拼 / 英文；双拼共 7 套方案（自然码、小鹤、搜狗、微软、紫光、智能ABC、加加），
  键面自动提示韵母与 zh/ch/sh。输入方案在设置页「拼音输入方案」里选（全拼 + 7 套双拼，选中即全局生效）。
  - 不完整拼音补全：`ni m` 或 `nim` 自动补全为 `ni + men` 召回「你们」。
  - 词库约束分词：`xuni` 正确切分为 `xu + ni`（虚拟）而非 `xun + i`（寻）。
  - 候选栏智能预测（默认关，设置页可开）；中英一键切换。
  - 候选栏上行显示你实际按下的键，双拼下不会因为转换吞字而看起来「没反应」。
  - 26 键区的圆角与间隙、整块键盘的透明度在设置页「键盘按钮调整」里调
    （圆角 0~24dp、间隙 0~8dp、透明度 0~100%），松手即时生效，无需重启。
  - 删除键：单击删 1 个字符；按住连删（拼音串删空后继续删已上屏文字）；
    双击后按住，连输入框里已上屏的文字一起清。
  - 候选栏最右侧的 ✕：一键清空当前拼音串与候选词（仅在候选/预测展示时出现），
    候选栏随即回到默认的 6 按钮功能面板。
- **剪贴板历史**（输入法内嵌面板）：复制内容自动保存，AES-256-GCM 加密后写进本机私有数据库，
  仅本输入法可读；分类为全部 / 网址 / 数字 / 收藏，动态序号，点击即粘贴，长按切换收藏或删除，
  清空需二次确认且不动收藏。
  - 顶部搜索面板：候选栏上方，输入关键词实时检索历史，结果可滚动、点击直接上屏，
    退出后恢复 26 键。
- **分类词库**（设置页「补充短语词库」）：按需下载长词包（约 18.5 万词条）和腾讯大词库（约 95.5 万词条）。
  下载后只在空闲时后台加载，不挡打字；增删词库会自动重启输入法。
- **用户词频学习**：你点过的候选会记住，下次排到前面。数据只留本机、不上传，设置页可关。
- **不打包任何模型**：APK 约 5.0MB，仅依赖 `core-ktx`、`activity-ktx`、`okhttp3`、`xz`。
- **（可选）语音听写**：长按 / 短按麦克风说话，识别文本实时回显并上屏。
  需自建 NAS 服务端，详见上方说明。
  - 长按麦克风：按住说话，松手即识别；按住时上滑再松手可取消。
  - 短按麦克风：进入连续录音，再点一下结束（最长 3 分钟兜底自动停止）。
  - 本地 VAD 静音检测：连续静音自动收尾，减少无效上传。

## 架构

拼音键盘、剪贴板、词库均在本机运行；语音听写为可选的旁路，需要外部服务端：

```
【本机 · 无需服务端】
   拼音键盘 / 剪贴板面板 / 词库引擎
        ↑
     输入法服务 JinnIme

【可选 · 需自建服务端】—————————————————————
   麦克风 → MicRecorder(16kHz PCM16) → AsrClient(WebSocket)
                                          ↓
                            家用 NAS 上的 CapsWriter Offline
                                          ↓
                          识别文本（整段累积，整体覆盖显示）
```

| 模块 | 职责 |
|---|---|
| `JinnIme` | 输入法服务：拼音 / 语音双模式、手势、结果回显、剪贴板粘贴（同进程回调） |
| `PinyinEngine` | 拼音引擎：词库加载（含可选包延迟加载）、候选查询、双拼、不完整补全、词库约束分词 |
| `KeyboardLayouts` | 键盘静态布局数据（QWERTY 行 / 数字层 / 符号分组含日文假名） |
| `PinyinKeyboardView` | 26 键键盘 + 候选栏 + 功能面板 |
| `ClipboardPanelView` | 剪贴板内嵌面板（分类 / 粘贴 / 收藏 / 删除 / 清空） |
| `SearchPanelView` | 顶部搜索面板：结果列表 + 搜索框（退出搜索用候选栏「退出」或回车 / 收起键盘） |
| `ClipboardDb` | 剪贴板历史 SQLite（AES-256-GCM 加密） |
| `DictManagerActivity` | 分类词库页：可选词库列表 + 下载 / 删除 |
| `KeyAppearanceActivity` | 键盘外观页：26 键区圆角 / 间隙 + 键盘透明度滑杆 |
| `SymbolOrderActivity` | 符号分组排序页：↑↓ 调整顺序 / 恢复默认 |
| `FavoriteSymbolsActivity` | 「收藏」符号编辑页：按页增删自定义符号 |
| `AsrClient` ⚠️ | WebSocket 客户端：音频流式上传、断线指数退避重连（**仅语音路径使用**）|
| `Diagnostics` | 诊断日志：文件输出 + 崩溃捕获 + Trace ID |

## 语音服务端（可选，需自建）

> 不用语音的话，这节可以直接跳过。

服务端为部署在**家用 NAS** 上的 CapsWriter Offline（Docker），端口 `6016`。

| 项 | 值 |
| --- | --- |
| 地址 | 家用 NAS 的局域网地址，如 `192.168.1.3` |
| 端口 | `6016` |
| 协议 | `ws://<host>:6016`，子协议 `binary` |
| 音频格式 | 16kHz / 单声道 / float32 小端裸样本（Base64） |
| 分段参数 | `seg_duration=60`、`seg_overlap=4` |

通信严格对齐服务端 `core/protocol.py`：一次听写由若干 `is_final=false` 音频包
+ 一个 `data=""` 的 `is_final=true` 收尾包组成；服务端返回**整段累积文本**，
客户端整体覆盖显示，绝不自行拼接。

## 构建

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
./gradlew assembleRelease   # Release（需 JINN_KEYSTORE_* 环境变量，否则未签名）
```

### 签名说明
开源仓库**不包含签名密钥与密码**，且**禁止把密钥库或口令文件放进仓库目录**（否则一旦打包/备份整个项目即随之外泄）。如需签名构建：

1. 在**仓库之外**生成密钥库，例如 `<凭据目录>/com.jinn.inputmethod/release.jks`：
   `keytool -genkeypair -keystore <凭据目录>/com.jinn.inputmethod/release.jks -alias jinn ...`
2. 用环境变量指向它（`build_apk.py` 的推荐路径）：
   ```bash
   JINN_KEYSTORE_ROOT='<凭据目录>' python build_apk.py
   ```
   或直接给 Gradle 传 `JINN_KEYSTORE_FILE` / `JINN_KEYSTORE_PASSWORD` / `JINN_KEY_ALIAS` / `JINN_KEY_PASSWORD`。
3. `./gradlew assembleRelease` 在**未提供上述配置时产出未签名包**（保留无密钥构建能力）。

> ⚠️ 用 `build_apk.py` 时签名流程**顺序不可调换**：
> 构建 → 去 META-INF → `zipalign -p 4` → `apksigner sign` → 校验。
> apksigner 不负责对齐，顺序写反会让 APK 未对齐、设备读资源需先解压。

## 词库

内置词库（`app/src/main/assets/`）：
- `pinyin_index.bin.xz`：基础包二进制索引，约 60 万键（词长 ≤4 字，含四字成语）；
  解压后按字节二分查找、按需解码，不再逐行解析、不再建哈希表
- `hot_phrases.txt.xz`：高频子集（4 万词，约 220KB）。先加载它，键盘弹出即可打字；
  全量索引随后在后台就绪（解压一次后落盘缓存，之后启动为内存映射复用，约 0.05 秒）
- `pinyin_chars.txt`：422 音节（音节 → 单字）
- `pinyin_syllables.txt`：合法音节全集
- 可选词库不进 APK：用户在「分类词库」页按需下载，存于 `filesDir/dicts/`，
  引擎启动时自动扫描

词库构建工具见 `tools/dict_builder/`（Rime → 项目格式）。THUOCL 那条自动注音的路线评估过，
没有采用：它的词条不带拼音列，注音错误率高。

## 许可证

本项目采用 **GNU GPL v3.0**（见 [LICENSE](LICENSE)）。

> ⚠️ 内置词库含 GPL-3.0 来源（雾凇 / 白霜拼音），按 GPL 传染性要求，
> 本项目整体以 GPL-3.0 分发。

## 致谢

- [CapsWriter Offline](https://github.com/HaujetZhao/CapsWriter-Offline) — 可选的语音识别服务端
- [iDvel/rime-ice（雾凇拼音）](https://github.com/iDvel/rime-ice) — 词库
- [mozillazg/pypinyin](https://github.com/mozillazg/pypinyin) — 注音工具
