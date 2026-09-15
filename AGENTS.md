# 项目说明：Jinn安卓输入法

安卓拼音输入法（IME）：26 键全拼 / 自然码双拼 + 剪贴板历史 + 分类词库，**全部本机运行**。

另有一项**可选**的语音听写：采集麦克风音频经 WebSocket 上传到**自建家用 NAS** 上的
CapsWriter Offline 服务端（`ws://<host>:6016`，子协议 `binary`）识别，本机零模型。
**该功能非开箱即用**（需用户自行部署服务端），且仅供作者自用，不属于通用功能。
配套服务端源码在本地 `docs/CapsWriter-Offline-master/`（不入库；协议对齐其 `core/protocol.py`）。

## 技术栈

| 层 | 技术 |
|---|---|
| 语言 | Kotlin（官方风格） |
| 构建 | AGP 8.5.2 + Kotlin 1.9.24，Gradle Wrapper 8.9（本机用本地 8.10.2 等效） |
| JDK | 17（compileOptions 与 jvmTarget 均 17） |
| SDK | compileSdk 34 / minSdk 26 / targetSdk 34（本机另有 android-36 可用） |
| 依赖 | 仅 core-ktx、activity-ktx、okhttp 4.12、xz（词库解压）、junit+org.json（测试） |

## 代码风格

- 文件命名：`PascalCase.kt`，与类名一致；资源 `snake_case`
- 每个类带 KDoc 注释说明职责与关键约束（协议、线程模型、生命周期）
- 回调线程语义必须在注释标注（OkHttp IO 线程 / 采集线程 / 主线程），接收方自行切主线程
- 错误处理：`runCatching` + `onFailure` 打日志后回退，绝不静默吞异常；不抛受检异常
- 配置一律走 `Prefs`（SharedPreferences），不散落魔法值；协议常量收敛在 `Protocol`/`AudioMessage`
- 空安全优先：可空返回用 `?:` 给默认值，`!!` 仅用于必然非空且带注释处

## 测试

- 运行：`.\gradlew.bat testDebugUnitTest`（纯 JVM，无需设备，几十秒出结果）
- Gradle 8.9 已装在 Wrapper 缓存；若 Wrapper 首次下载失败（SSL 握手被拒），用腾讯云镜像
  `https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip` 下载后解压到
  `~/.gradle/wrapper/dists/gradle-8.9-bin/<hash>/` 并建 `gradle-8.9-bin.zip.ok`
- 模式：`app/src/test/java/...`，JVM 单测（JUnit 4），无需设备
- 覆盖（13 个测试类 / 182 个用例）：
  - 协议：`ProtocolTest`（序列化 / 解析）
  - 拼音引擎：`PinyinEngineTest` / `ShuangpinTest` / `PinyinCompletionTest`
  - 词库：`PhraseDictIntegrityTest`（词库完整性）、`RareCharsFilterTest`（生僻字过滤）、
    `OptionalDictMergeTest`（可选包合并去重）、`CandidateCountBoundTest`（候选数量边界）
  - 剪贴板：`ClipboardClassifierTest`、`ClipboardClassifierBoundaryTest`（分类边界，
    含 CRLF / 长度边界 / 负例）、`ClipboardDedupeTest`、`ClipboardFilterTest`
  - 文字拖选：`TextSelectionTest`
- 注意：测试 KDoc 注释里禁止出现 `*/`（会提前终止块注释导致编译失败）

## 构建与运行

- Debug：`gradlew.bat assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Release：`gradlew.bat assembleRelease`，或用 `python build_apk.py` 走「构建→zipalign→apksigner 签名」
  （**顺序不可调换**：apksigner 不负责对齐，写反会让 APK 未对齐、设备读资源需先解压）
- 真机安装：`adb install -r jinn-release.apk`（覆盖安装签名一致，不丢数据）
- 真机输入法：`adb shell ime set com.jinn.inputmethod/.JinnIme`（实际包名是 `com.jinn.inputmethod`）
- 本机 SDK：`C:\Android\sdk`（local.properties 已写 sdk.dir），JDK17 在 PATH

## 项目结构

```
app/src/main/java/com/jinn/inputmethod/
├── Protocol.kt         # 协议常量 + AudioMessage(发) / RecognitionMessage(收) 序列化
├── Prefs.kt            # SharedPreferences 配置（host/port/language/prompt/生僻字开关）
├── MicRecorder.kt      # 16kHz PCM16 采集 → float32 小端；本地 VAD 静音检测
├── AsrClient.kt        # OkHttp WebSocket：beginTask/sendChunk/endTask + 断线指数退避重连
├── MicButton.kt        # 麦克风按钮（纯绘制，手势判定在 IME）
├── JinnIme.kt          # 输入法服务：语音键盘 + 拼音键盘双模式、长按/短按手势、结果回显、剪贴板粘贴广播
├── PinyinEngine.kt     # 拼音引擎：词库加载（ConcurrentHashMap + 流式解压，可选包延迟加载）、候选查询、自然码双拼
├── KeyboardLayouts.kt  # 键盘静态布局数据（QWERTY 行定义 / 数字层映射 / 符号分组含日文假名）
├── PinyinKeyboardView.kt # 拼音键盘视图：26 键 QWERTY + 候选栏 + 功能面板（全拼/剪贴板/方向/粘贴/收起）+ 智能预测
├── PinyinKey.kt        # 拼音键盘单键（纯绘制：字母 + 双拼韵母/声母提示）
├── TextSelection.kt    # 文字拖选核心逻辑（Anchor/Focus 模型，纯函数可单测）
├── SettingsActivity.kt # 设置页（服务端/语言/提示词/授权/剪贴板/分类词库入口）；保存后杀进程重启 IME
├── DictManagerActivity.kt # 分类词库页（可选词库列表 + 下载 / 删除）
├── OptionalDicts.kt    # 可选词库清单（文件名 / 说明 / 体积 / 启动耗时 / 下载源）
├── Diagnostics.kt      # 诊断日志：文件输出 + 崩溃捕获 + logcat 快照（V 级默认不落盘）
├── BackgroundIo.kt     # 单线程后台 IO 调度器（所有 DB/解密走这里，主线程零阻塞）
│
├── ClipboardController.kt   # 剪贴板监听（PrimaryClipChangedListener）+ 自动分类 + 保存到库
├── ClipboardStore.kt(内)    # ClipboardController 内 object：自动分类→加密入库纯逻辑
├── ClipboardDb.kt           # 剪贴板历史 SQLite（AES-256-GCM 密文 + category/is_favorite/is_private + 去重/裁剪/搜索）
├── ClipboardCrypto.kt       # 加密工具（Android Keystore AES-256-GCM，base64(iv):base64(cipher)）
├── ClipboardClassifier.kt   # 自动分类（URL/NUMBER/OTHER，纯逻辑可单测；隐私绝不自动）
├── ClipboardPrefs.kt        # 剪贴板配置（独立 SharedPreferences：enabled/maxItems/root 增强）
├── ClipboardFirewall.kt    # Root 增强：数据目录安全审计（不做任何清空系统剪贴板操作）
├── ClipboardPanelView.kt   # 键盘内嵌剪贴板面板（列表 UI 与搜索面板重复，改一处要同步两处）
└── SearchPanelView.kt      # 顶部剪贴板搜索面板（剪贴板内容的第二个展示入口，同上）

> `ClipboardDb.kt` 内含顶层 `ClipboardFilter`：负责把分类栏的伪分类
> （FAVORITE / PRIVATE，实为独立标签列）翻译成 SQL 参数。**收藏/隐私绝不能当
> category 传给 SQL**，否则列表恒空；该规则由 `ClipboardFilterTest` 守卫。
```

## 诊断日志（重要）

- 输出目录：`/storage/emulated/0/JinnIme/logs/jinn-YYYY-MM-dd.log`（按天滚动，保留 7 天）
- 无共享存储写权限时自动退回 `Android/data/<pkg>/files/logs/`
- 真机 root 环境（KernelSU）下先 `su -c 'appops set <pkg> MANAGE_EXTERNAL_STORAGE allow'`
  使应用可直写共享目录；应用侧 `Diagnostics.init()` 也会尝试 appops+chown，兜底 app 专属目录
- 崩溃自动写入 stack trace 并 dump `logcat-<ts>.log` 快照到同目录
- 所有关键路径已埋点（连接/重连/收发/录音/VAD/权限/剪贴板保存/词库加载），排查先用 `cat` 日志文件
- 排查命令：`adb shell cat /storage/emulated/0/JinnIme/logs/jinn-*.log`
- `Diagnostics.i/v/w/e` 同时写 logcat 与日志文件；`PinyinEngine` 加载完成会打
  「词库加载完成: 音节=N 词语键=N」与耗时（后台线程，基础包约 6.4s，不阻塞 UI）
- 可选词库为**延迟加载**：`loadOptionalAsync` 在基础包就绪 5 秒后于后台补齐，
  因此「开机后首次输入的候选就绪」不被大词库拖慢（详见 `PinyinEngine` 注释）
- 剪贴板保存打 `save: 已保存 … (分类=...)`；剪贴板页刷新打 `refresh: 全库=N 分类=... 查询=N`

## 约定

- **禁止修改语音部分**：`MicRecorder`、`AsrClient`、`Protocol`、`MicButton`，以及 `JinnIme`
  中的录音、WebSocket 连接、识别结果处理链路，均为作者自用，**不得改动**——语音功能的
  外部可用性不在考虑范围内。若某问题必须改语音才能修，只登记不修并说明原因。
- 提交风格：中文短语，`动词 + 对象`（如「修复设置页布局崩溃」）
- 单模块 `:app`，无多模块拆分；不改协议字段名（服务端 from_dict 严格校验）
- 通信帧：一次听写 = 若干 `is_final=false` 音频包 + 一个 `data=""` 的 `is_final=true` 收尾包；
  服务端返回的是**整段累积文本**，客户端整体覆盖显示，绝不自行拼接；取消也发收尾包按 taskId 丢弃
- 字体渲染、键盘布局改动需在真机截图确认（编译期无感，只有真机才看得出）
- `docs/` 是**本地历史资料目录**（调研、设计稿、排查记录、接手指南等 90+ 文件），
  **不推送 GitHub**、不需要处理/完善，保持只读。
  **新增这类资料请直接放 `docs/`，不要放根目录**（`.gitignore` 已忽略 docs/ 与 release/）。
- **根目录只保留 4 个文档**：`README.md`（中文，主）/ `README_EN.md`（英文）/
  `AGENTS.md`（本文件）/ `更新日志.md`（按轮次记录）。其余历史文档一律归档到 `docs/`。

### 剪贴板模块关键约束

- **第三方 APP 不得读取 History**：JinnIme 不提供任何 History API（ClipboardHistoryProvider/PermissionStore/PermissionActivity 已全部移除）。第三方 APP 只能读取 Android System Clipboard 的最新内容，无法访问 JinnIme 私有历史库
- **不干预 System Clipboard**：JinnIme 不阻断、篡改或周期性清空 Android System Clipboard；网盘、购物、分享类 APP 识别口令/链接的功能不受影响
- **入库去重**：每条内容写入时按 `content_hash` 唯一约束原子 upsert——同内容已存在则更新元数据并置顶（不动收藏/隐私标记），不存在则 INSERT。**禁止依赖「打开面板时全表 deduplicate」维持数据正确性**（面板打开只负责读取快照渲染）
- **分类**：固定 全部/网址/隐私/数字/收藏；隐私**只能用户手动标记**，绝不自动；收藏是独立标签可与分类并存
- **序号**：UI 序号非 DB ID，最新=最大，删除/去重后重新连续编号；搜索保留原始序号
- **点击粘贴**：面板点击条目 → 广播（`ACTION_CLIPBOARD_PASTE`）回传 IME → `commitText`；
  连接无效时暂存 `pendingPasteText`，`onStartInputView` 时自动提交；无效连接不崩溃
- **ownCommit 陷阱**：`onOwnCommit()` 只在真正写系统剪贴板的粘贴路径调用；打字/语音上屏走 `commitText`
  不写系统剪贴板，**严禁**在 `commit()` 里调 `onOwnCommit()`——否则标记残留会把用户真实复制误杀
- **加密**：正文 AES-256-GCM（Android Keystore），`base64(iv):base64(cipher)`；绝不落明文日志