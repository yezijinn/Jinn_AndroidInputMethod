# Jinn 安卓输入法 · 接手指南

> 面向准备接手本项目的开发者。内容基于对当前代码（28 个 Kotlin 源文件，8293 行）的逐文件通读，
> 标注了**代码与文档不符之处**与**已确认的缺陷**，请优先阅读第 6、7 节。
>
> 文档生成时间：2026-09-15 ｜ 对应提交：`941ca68`（配置统一签名打包流程，2026-08-30）

---

## 1. 它是什么

一个**极简安卓输入法**，两件事：

| 能力 | 做法 |
|---|---|
| 语音输入 | 采集麦克风 → WebSocket 实时上传 → 局域网 NAS 上的 CapsWriter Offline 服务端识别 → 文本回显上屏。**本机零模型** |
| 键盘输入 | 26 键拼音键盘，全拼 / 自然码双拼，雾凇+白霜词库（29MB），带智能预测与文字拖选 |
| 剪贴板 | 监听系统剪贴板 → 自动分类 → AES-256-GCM 加密入库 → 键盘内嵌面板或独立页面调用 |

**设计取向**：依赖极简（仅 OkHttp + core-ktx + activity-ktx + shizuku）、全部 UI 手写 XML/代码、
不引入任何输入法框架（无 Jetpack Compose、无 Room、无 Dagger/Hilt、无协程）。
代价是大量手写的状态机和自绘逻辑，多处逻辑重复（见 6.3）。

**运行环境强绑定**：识别依赖飞牛 NAS 上跑的 CapsWriter Offline 服务端（`ws://<host>:6016`，
子协议 `binary`）。服务端源码在仓库 `CapsWriter-Offline-master/`（Python，协议对齐 `core/protocol.py`）。
**断网 = 语音功能完全不可用**，这是架构级依赖。

---

## 2. 整体架构与模块划分

```
┌──────────────────────────────────────────────────────────────┐
│                        系统 / 应用层                           │
│   SettingsActivity（唯一桌面入口）  DictManagerActivity       │
│            │ 改配置后杀进程重启                 │ 分类词库下载 │
└────────────┼───────────────────────────────────────┼───────────┘
             │                                       │
┌────────────▼───────────────────────────────────────▼───────────┐
│                    JinnIme : InputMethodService                 │
│  （中枢：1184 行，持有全部模块，所有跨模块调用都经它转发）          │
│  ┌─────────────┬──────────────┬───────────────┬──────────────┐ │
│  │ 语音链路     │ 拼音键盘      │ 剪贴板         │ 词库          │ │
│  │ MicRecorder │ PinyinKbdView│ Controller    │ PinyinEngine │ │
│  │ AsrClient   │ PinyinEngine │ ClipboardDb   │ Accessibilty │ │
│  │ Protocol    │ TextSelection│ Crypto        │ OptionalDict │ │
│  └─────────────┴──────────────┴───────────────┴──────────────┘ │
│  横切：Diagnostics（文件日志+崩溃捕获） / Prefs / BackgroundIo    │
└─────────────────────────────────────────────────────────────────┘
             │ WebSocket (ws://host:6016, binary)
┌────────────▼────────────────────────────────────────────────────┐
│          CapsWriter Offline 服务端（飞牛 NAS，Python）            │
└─────────────────────────────────────────────────────────────────┘
```

**模块划分（按职责）**

| 模块 | 文件 | 职责边界 |
|---|---|---|
| 语音链路 | `MicRecorder` `AsrClient` `Protocol` `MicButton` | 采集 → 编码 → 传输 → 结果解析 |
| 拼音键盘 | `PinyinKeyboardView` `PinyinKey` `PinyinEngine` `TextSelection` | 键位、候选、双拼、拖选 |
| 剪贴板 | `ClipboardController` `ClipboardDb` `ClipboardCrypto` `ClipboardClassifier` `ClipboardPrefs` + 三个 UI | 监听、加密、存储、检索、粘贴 |
| 词库 | `PinyinEngine` `OptionalDicts` `DictManagerActivity` `KeyboardLayouts` | 基础包内置 + 可选包按需下载、延迟加载 |
| 横切 | `Diagnostics` `Prefs` `BackgroundIo` `SettingsActivity` `ClipboardFirewall` | 日志、配置、线程、设置、审计 |

**架构特点**：`JinnIme` 是事实上的上帝对象——它直接持有并串联所有模块，
键盘与 IME 之间通过 `PinyinKeyboardView.Listener`（14 个回调）通信，
剪贴板页面与 IME 之间通过**广播**通信（因为 Activity 拿不到 `InputConnection`）。

---

## 3. 目录结构与关键文件

```
app/src/main/java/com/jinn/inputmethod/   28 个 Kotlin 文件，8293 行
├── JinnIme.kt  ⭐ 输入法服务，全部模块的持有者与调度中枢
├── PinyinKeyboardView.kt  ⭐ 拼音键盘（最大文件，LinearLayout + XML 键位）
├── PinyinEngine.kt  ⭐ 词库加载 + 候选查询 + 智能预测 + 自然码双拼
├── SettingsActivity.kt  设置页（桌面入口，改配置后杀进程重启 IME）
├── ClipboardPanelView.kt          键盘内嵌剪贴板面板（剪贴板第一入口）
├── ClipboardDb.kt  SQLite（密文存储、去重、裁剪）
├── SearchPanelView.kt  键盘内嵌搜索面板
├── Diagnostics.kt  文件日志 + 崩溃捕获 + 事件环形缓冲
├── AsrClient.kt  OkHttp WebSocket + 指数退避重连
├── MicRecorder.kt  AudioRecord 采集 + PCM16→float32 + 本地 VAD
├── PinyinKey.kt  单键自绘（圆角矩形 + 字母 + 双拼提示）
├── ClipboardController.kt  系统剪贴板监听 + ClipboardStore 保存逻辑
├── Prefs.kt  SharedPreferences 主配置
├── MicButton.kt  麦克风按钮（纯绘制）
├── ClipboardFirewall.kt  Root 增强：只读安全审计（7 项）
├── ClipboardClassifier.kt  自动分类（URL/NUMBER/OTHER）
├── ClipboardCrypto.kt  Android Keystore AES-256-GCM
├── TextSelection.kt  拖选纯函数（可单测）
├── Protocol.kt  ⭐ 协议常量 + 收发消息序列化
├── ClipboardPrefs.kt  剪贴板独立配置
├── BackgroundIo.kt  单线程后台 IO 调度器

app/src/main/assets/          29MB 词库（pinyin_phrases.txt 占 29MB）
app/src/test/java/...           7 个测试类，~100 个用例（纯 JVM）
```

**非代码关键资产**

| 路径 | 说明 |
|---|---|
| `CapsWriter-Offline-master/` | 服务端 Python 源码，协议对齐的**唯一权威参考** |
| `build_apk.py` | 一键打包：统一密钥 → assembleRelease → apksigner 重签 V2+V3 |
| `tools/dict_builder/` | 4 个 Python 脚本，词库生成/扩充/对比 |
| `tools/bug_capture.ps1` | ADB+Root 抓日志/logcat/dumpsys/剪贴板 DB |
| `jinn-release.apk` | 11.1MB 已签名产物（tag `v20260830`） |
| `ses_fb2d904cbffewGCRN4iuzDTDSg.json` | 3.6MB，**来源不明且未被 .gitignore 忽略**，建议先确认再决定是否删除 |

---

## 4. 核心技术栈与依赖

| 层 | 选择 |
|---|---|
| 语言 | Kotlin 1.9.24（官方风格，无协程） |
| 构建 | AGP 8.5.2 + Gradle Wrapper 8.9 |
| JDK | 17（source / target / jvmTarget 全 17） |
| SDK | compileSdk 34 / minSdk 26 / targetSdk 34 |
| UI | **传统 View 体系**，无 Compose。键盘为 XML 键位 + 少量 Canvas 自绘 |
| 并发 | 裸 `Thread` + `Handler` + 单线程 `ExecutorService`，**无 Kotlin 协程** |

**运行时依赖（仅 4 个）**

```kotlin
androidx.core:core-ktx:1.13.1
androidx.activity:activity-ktx:1.9.0        // registerForActivityResult
com.squareup.okhttp3:okhttp:4.12.0          // WebSocket（唯一重量级依赖）
dev.rikka.shizuku:api:13.1.5                // 免 root 获取 adb 权限（可选）
```

**测试依赖**：`junit:4.13.2` + `org.json:json:20240303`（JVM 单测，无需设备）

**签名**：环境变量优先（`JINN_KEYSTORE_FILE` / `_PASSWORD` / `JINN_KEY_ALIAS` / `JINN_KEY_PASSWORD`），
回退 `keystore.properties`；`JINN_APKSIGNER_ONLY=1` 时 Gradle 不签名、由 apksigner 签。
V1 关闭、V2+V3 开启。`versionCode` = 编译日期 `yyyyMMdd`（天然单调）。

---

## 5. 输入法服务与生命周期

### 5.1 完整生命周期流程

```
[首次启用]
用户系统设置启用 → 系统绑定 JinnIme
  ↓
onCreate()  ← 只跑一次（进程级）
  ├─ Diagnostics.init()            日志目录 + 崩溃捕获
  ├─ asr.connect()                 预热 WebSocket（关键：避免首次点击"没反应"）
  ├─ Thread { PinyinEngine.load() } 后台加载 29MB 词库（约 7s，不阻塞 UI）
  ├─ ClipboardController.start()   注册系统剪贴板监听（若开启）
  ├─ registerNetwork()             监听网络恢复 → 主动重连
  └─ registerReceiver × 2          配置广播 + 剪贴板粘贴广播
  ↓
onCreateInputView()  ← 可能多次（配置变化时重建）
  └─ FrameLayout 容器 + 语音视图 + 拼音视图（双视图叠放，靠 visibility 切换）
  ↓
[每次点输入框]
onStartInputView(info, restarting)
  ├─ asr.connect()                 确保连接
  ├─ pinyinKeyboard.configure()    重新套用双拼/中英文方案
  └─ flushPendingPaste()           提交剪贴板页暂存的粘贴文本
  ↓
[使用中] 录音 / 打字 / 剪贴板面板
  ↓
[收起键盘]
onFinishInputView()  → 停止录音（丢弃）、提交未上屏候选
onFinishInput()      → 清理所有延时任务
  ※ 注意：全程不关闭 WebSocket（松手后服务端 final 结果还要 1~3 秒）
  ↓
onDestroy()  → 后台线程释放 AudioRecord + 关闭 socket（避免主线程 ANR）
```

### 5.2 生命周期的关键约定（改代码前必读）

| 约定 | 原因 |
|---|---|
| **WebSocket 不随输入会话关闭** | 松手后服务端 final 结果要 1~3 秒才回来，此刻关连接会丢结果。只在 `onDestroy` 释放 |
| **录音中切走 / 收起键盘 → 丢弃结果** | 否则文本会落到别的输入框 |
| `onEvaluateFullscreenMode() = false` | 键盘很矮，横屏不进全屏抽取模式 |
| 视图用 `GONE/VISIBLE` 切换而非重建 | 重建会触发 IME 窗口反复 relayout（有实证：12:20 循环日志） |
| 释放录音必须切后台线程 | `recorder.stop()` 含 `join(300ms)`，主线程做会 ANR |

### 5.3 语音输入完整链路

```
按住/点击麦克风
  ↓ onMicTouch(): DOWN → 260ms 后 startHoldRunnable → Mode.HOLD（长按）
  ↓                UP 且未达阈值 → Mode.TOGGLE（短按=连续录音）
startRecording()
  ├─ 检查 RECORD_AUDIO 权限
  ├─ requestAudioFocus()         被来电/其他 App 抢占时自动停止录音
  ├─ asr.beginTask() 返回 null?  → 未连接，放弃（并释放已申请的音频焦点）
  └─ recorder.start()            采集线程 "jinn-mic"
  ↓
采集循环（每 100ms 一包）
  AudioRecord(PCM16) → toFloat32LittleEndian() → onChunk → asr.sendChunk()
                                              → Base64 → AudioMessage.toJson() → WS send
  同时算 RMS → onLevel（电平动画）+ 本地 VAD（连续静音 120s → onSilence）
  ↓
松手 / 静音 / 超 3 分钟 / 掉线
  ↓
stopRecording(commit)
  ├─ commit=true  → endTask()  发 data="" 的 is_final 包 → 等服务端 final 文本
  └─ commit=false → cancelTask() **同样发收尾包**（清理服务端按连接持有的音频缓冲），
                                 但 acceptingTask 置空 → 结果按 taskId 丢弃
  ↓
onMessage → RecognitionMessage.parse → 校验 taskId == acceptingTask → onResult
  ↓
handleResult()
  ├─ !isFinal → setComposingText() 实时回显（默认关闭，见 Prefs.useComposing）
  └─ isFinal  → 整体覆盖提交
```

**协议铁律（改 `Protocol.kt` 前必读）**

1. 服务端 `AudioMessage.from_dict` **严格校验字段名**，多一个少一个都拒。手写 JSON，别用反射序列化。
2. 服务端返回的 `text` 是**整段累积文本**，不是增量。客户端整体覆盖，**绝不自行拼接**。
3. 取消也要发 `is_final` 收尾包——服务端音频缓冲是**按连接**而非按任务持有的，
   不收尾会让残留音频串到下一次听写。
4. 音频格式：16kHz / 单声道 / **float32 小端裸样本**（用兼容性最好的 PCM_16BIT 采集后手动转）。

### 5.4 键盘模式切换

```
KeyboardMode.VOICE  ←→  KeyboardMode.PINYIN
   语音键盘                26 键拼音键盘
（FrameLayout 内两个子视图，applyKeyboardMode() 切 visibility）

默认模式由 prefs.defaultKeyboardMode 决定（默认 26 键全拼中文）
每次 onCreateInputView 都重新套用（因为 InputMethodService 可能复用实例）
```

---

## 6. 核心功能逻辑

### 6.1 语音：连接与重连

- OkHttp 单例（所有 `AsrClient` 共享），`proxy(Proxy.NO_PROXY)` 局域网直连，
  `pingInterval=20s` 心跳防 NAT 掐线，`readTimeout=0` 长连接不设读超时。
- 断线指数退避：1s → 2s → 4s … 上限 30s（1 shl 8）。
- **`closeSocket()` 先置 `socket = null` 再 close**：旧 listener 的回调因 `webSocket !== socket`
  提前 return，不会重复调度重连。这是防止重连风暴的关键写法。
- **录音中掉线**：`renderLink()` 收到 OFFLINE 且 `mode != NONE` 时主动 `stopRecording(commit=false)`，
  避免用户白说。

### 6.2 拼音键盘

- **不是纯 Canvas 自绘**：`PinyinKeyboardView` 继承 `LinearLayout` + inflate XML，
  只有叶子控件 `PinyinKey`、`MicButton` 是 Canvas 自绘。
- 状态四元组：**层（字母/符号/数字）× 全拼/双拼 × 大小写 × 面板**。
  `capsMode` 优先级最高，会屏蔽中英与方案切换。
- 候选组织：输入 N 个音节 → 先 N 字词、再 N-1 字词 … 最后单字（对齐 AOSP PinyinIME）。
- 分词：先试「词库约束分词」（DFS 枚举所有合法切分，上限 16 条路径，按词库命中评分），
  失败退回贪心最长匹配。解决了 `xuni → [xu,ni]` 虚拟 vs `[xun,i]` 的歧义。
- **消费区间（Residual Pinyin Rematching）**：`PinyinEngine.consumption()` 按候选实际拼音
  决定消费多少输入字符，实现"选词后残码保留"（如 `nihao` 选「你」保留 `hao`）。
- 自然码双拼：`Shuangpin.toQuanpin()` 纯函数，两键一音节，撮口呼全部归一化为 ASCII。

### 6.3 剪贴板

```
系统剪贴板变化 → ClipboardController.onClipboardChanged()
  ├─ ownCommit 且 3s 内？→ 跳过（自身粘贴造成的回环）
  ├─ primaryClip == null？→ BackgroundIo 延迟 250ms 重试（Android 10+ 时序问题）
  └─ ClipboardStore.save() → 分类 → AES-GCM 加密 → db.upsert()
                                                     ↓
                                        content_hash 唯一 → 已存在则更新+置顶
                                        （不动收藏/隐私标记）；不存在则 INSERT
                                        超上限裁剪最旧非收藏
```

**三条不可违反的规则**（都在注释里写明，踩过坑）：

1. **不干预系统剪贴板**——不阻断、不篡改、不周期性清空。网盘/购物/分享类 APP 识别口令的功能不受影响。
2. **`onOwnCommit()` 只在真正写系统剪贴板的粘贴路径调用**。打字/语音上屏走 `commitText`
   不写剪贴板，**严禁**在 `commit()` 里调 `onOwnCommit()`——否则标记残留会把用户真实复制误杀。
3. **第三方 APP 无法读取历史库**——`ClipboardHistoryProvider` / `PermissionStore` /
   `PermissionActivity` 已全部移除，不提供任何 History API。

**加密**：Android Keystore AES-256-GCM，存储格式 `base64(iv):base64(cipher)`。
密钥句柄有内存缓存（`getKey` 每次走 binder/TEE 要 5-20ms，批量解密时是耗时大头）。

**分类**：`URL`（http/深链接/IP/域名）→ `NUMBER`（手机号/验证码/金额/纯数字串）→ `OTHER`。
隐私**绝不自动判断**。UI 分类栏：全部 / 网址 / 数字 / 收藏（收藏是独立标签，可与分类并存）。

**粘贴链路（跨进程难点）**：

```
Activity 点击记录 → 广播 ACTION_CLIPBOARD_PASTE（setPackage + 明文 + itemId）
  → JinnIme.clipboardPasteReceiver → pasteClipboardText()
      ├─ 有 InputConnection → commitText → 成功
      └─ 无连接 → 暂存 pendingPasteText + requestShowSelf
                  → 下次 onStartInputView → flushPendingPaste() 自动提交
  → 回传 ACTION_CLIPBOARD_PASTE_RESULT（校验 itemId 防串线）→ 成功才 finish()
```

### 6.4 可选词库（分类词库页）

基础包（约 60 万键，词长 ≤4 字含四字成语）随 APK 内置；长词包、腾讯大词库等
**不进 APK**，用户在设置页 →「分类词库」按需下载：

| 环节 | 实现 | 注意 |
|---|---|---|
| 清单 | `OptionalDicts.ALL` | 新增词库只需加一条记录 + 传 Release 附件 |
| 下载 | `DictManagerActivity`，多源回退（Gitee 优先、GitHub 备用）| 写临时文件再 rename，避免半个文件被当成有效词库 |
| 落地 | `filesDir/dicts/<fileName>` | 引擎启动时扫描整个目录并 merge |
| 生效 | 下载/删除后自动重启 IME 进程 | 词库只在 IME 启动时加载 |
| 加载 | **延迟加载**：`loadOptionalAsync(delayMs = 5000)` | 基础包就绪 5 秒后于后台补齐，不拖慢首次输入 |

**关键**：可选包加载后**必须重新 `finalizeLoad()`** —— `sortedPhraseKeys` 是加载时快照，
不重建则新词不参与前缀补全。

**并发**：延迟加载在后台线程 merge，而用户此刻正在打字（主线程 `query()`），
因此词库容器必须是 `ConcurrentHashMap`，有序表用 `@Volatile` 不可变快照整体替换。

---

## 7. 问题状态（2026-09-15 复核）

原文列出 P1–P10 十个问题。**其中大半已在后续多轮修复中解决**，本节按当前实际状态重列，
避免接手者按旧清单去修已经不存在的问题。

### 7.1 已修复（历史记录，无需再处理）

| 原编号 | 问题 | 现状 | 证据 |
|---|---|---|---|
| **P1** | 隐私分类「只有字段没有 UI」 | ✅ **已可用** | `ClipboardPanelView` 分类栏含「隐私」Tab，长按菜单可标记；隐私内容默认掩码、点击展开 |
| **P2** | 按键按压反馈失效 | ✅ **已修** | `PinyinKey.setPressedVisual(Boolean)` 已加，监听器调用它 |
| **P4** | 29MB 词库内存压力 | ✅ **已大幅改善** | 词库整体换 rime-ice：xz 5.65MB → **4.5MB APK**，加载从 8550ms → 6602ms |
| **P8** | 三份重复的列表 UI | ✅ **已减为两处** | 独立历史页（719 行，`ClipboardHistoryActivity`）已确认有意下线并删除 |
| **P9** | 广播携带剪贴板明文 | ✅ **已修** | 粘贴广播**只传 itemId**，正文由 IME 按 id 从库读回（规避 Binder 1MB 事务上限）|

### 7.2 当前仍存在（接手后可关注）

| 严重度 | 问题 | 说明 |
|---|---|---|
| 🟡 **中** | **粘贴状态机无超时保护** | `pendingPasteText` 暂存后等 `onStartInputView` 提交，若用户长时间不再唤起键盘，暂存内容会一直留着。当前无超时清理（已确认代码中无 `PENDING_TIMEOUT` 类机制）|
| 🟡 **中** | **剪贴板搜索为 O(n) 全库解密** | 搜索需逐条解密比对（不建明文全文索引，是隐私换性能的有意取舍）。历史条目多时搜索会变慢 |
| 🟢 **低** | **`ClipboardFirewall` 依赖 Root** | 7 项只读审计经 `su -c` 执行。**路径已加单引号**（不再依赖「路径恰好无特殊字符」），但本质上仍是 Root 能力面；无 Root 时整体降级不执行 |

### 7.3 原始清单中的其他项（未逐一复核）

P3（`refreshConfig()` 死代码）、P6（搜索复杂度，见 7.2）、P10（其余小问题）
未在本次复核中逐一验证，**保留原判断供参考**，需要时请以代码为准重新确认。

**核对方法**：`grep -rn "关键词" app/src/main/java/com/jinn/inputmethod/` —— 本节的
每一条结论都可用此方式复核。

---

## 8. 技术风险清单

| 风险 | 等级 | 说明与应对 |
|---|---|---|
| **服务端单点依赖** | 高 | NAS 宕机/换 IP/换 WiFi → 语音全线瘫痪。有网络监听重连，但服务端不可用时无降级方案 |
| **协议强耦合** | 高 | 服务端 `from_dict` 严格校验，改字段名即断。**改协议必须同步改 `CapsWriter-Offline-master`** |
| **IME 进程内存** | 高 | 29MB 词库常驻，无实测数据，低端机可能被 LMK 杀 |
| **测试覆盖不足** | 中 | 7 个测试类只覆盖纯函数（协议/拼音/双拼/拖选/分类/去重）。**AsrClient、MicRecorder、JinnIme、ClipboardDb 真实 SQL、Crypto、全部 UI 零覆盖** |
| **剪贴板明文暴露面** | 中 | 广播携带明文，依赖导出标志保护 |
| **29MB APK 体积** | 中 | 发布包 11.1MB（压缩后），词库无法按需下载 |
| **无障碍服务合规** | 中 | 上架应用市场时无障碍权限需要额外说明，目前国内市场的审核趋严 |
| **文档滞后** | 中 | `AGENTS.md` / `HANDOFF.md` / `README.md` 有多处与代码不符（见第 9 节） |

---

## 9. 文档与代码的出入（重要）

接手时**不要全信现有文档**。已确认的不一致：

| 文档说法 | 实际情况 |
|---|---|
| `AGENTS.md`：分类固定「全部/网址/隐私/数字/收藏」 | 只有 4 个 Tab，**隐私不存在** |
| `AGENTS.md`：「隐私内容默认隐藏明文，点击一次才显示再点才粘贴」 | 无此实现 |
| `AGENTS.md`：「设置页保存后立即生效（广播通知）」 | 广播是死代码，实际靠**杀进程重启**（800ms 后 killProcess） |
| `HANDOFF.md` §8.2「设置页测试 NAS 地址和端口」 | **功能不存在**，只有不联网的本地回显 `sendImeTest()` |
| `HANDOFF.md` §7.2 测试命令写死 `$env:LOCALAPPDATA\Temp\opencode\gradle-8.10.2` | 机器强相关，应改用 `.\gradlew.bat` |
| `HANDOFF.md` 项目名 `JinnVoiceIME` | 产品名已是「Jinn安卓输入法」 |
| `README` 签名说明只讲 `keystore.properties` | 实际主路径是 `build_apk.py` + `JINN_KEYSTORE_ROOT` 统一密钥 |
| `README`「APK 约 10MB」 | 实际 11.1MB |
| `AGENTS.md` 模块清单 | 漏了 `BackgroundIo`、`ClipboardPanelView`、`SearchPanelView`、`PinyinCompletionTest`、`ClipboardDedupeTest` |

---

## 10. 后续维护重点关注

### 10.1 上手第一周建议

1. **跑通单测**：`.\gradlew.bat testDebugUnitTest`（7 个类 ~100 用例，纯 JVM，几秒钟）。
   这是唯一能快速验证你没改坏东西的手段。
2. **读日志而非猜**：所有关键路径都埋了点。
   `adb shell cat /storage/emulated/0/JinnIme/logs/jinn-*.log`，
   或用 `tools/bug_capture.ps1` 一键抓全量。排查问题**先看日志**。
3. **抓一次内存**：`adb shell dumpsys meminfo com.jinn.inputmethod`，建立基线。
4. **确认 ses_*.json 是什么**，能删就删并加进 .gitignore。

### 10.2 改动时的硬约束（血泪总结，都在注释里）

| 约束 | 原因 |
|---|---|
| 改 `Protocol.kt` 字段名必须同步改服务端 | `from_dict` 严格校验 |
| 识别结果**整体覆盖，绝不拼接** | 服务端返回的是累积文本 |
| 取消也要发 `is_final` 收尾包 | 服务端缓冲按连接持有，不收尾会串音 |
| `commit()` 里**严禁**调 `onOwnCommit()` | 标记残留会误杀用户真实复制 |
| 不阻断/篡改/清空系统剪贴板 | 会破坏网盘、购物、分享类 APP 的口令识别 |
| 所有 DB / 解密操作走 `BackgroundIo` | 主线程零阻塞（AES + SQLite 都很慢） |
| 日志**禁止输出剪贴板正文** | 隐私红线 |
| 剪贴板三处列表 UI 必须同步修改 | 代码重复，改一处漏两处 |
| 列表空时 `setSelection(0)` 会崩 | 必须先 `isNotEmpty()` 判空 |
| 分类切换/去重/删除后必须滚回顶部 | 否则切回长列表只见底部几条 |
| IME 内禁用 `AlertDialog` | 无窗口 token，会崩 |
| 改键盘 layoutParams 必须匹配父容器类型 | 曾因用错 `FrameLayout.LayoutParams` 导致 ClassCastException |
| 键盘布局 / 字体渲染改动必须真机截图确认 | 编译期无感 |
| 测试 KDoc 注释里禁止出现 `*/` | 会提前终止块注释导致编译失败 |

### 10.3 建议的改进优先级

1. **修 P2 按键反馈**（用户每天都能感知的手感问题，改动小）
2. **补齐或移除隐私功能**（P1，消除文档与代码的分裂）
3. **清理 P3 死代码**（决定配置生效到底走哪条路）
4. **测内存基线**（P4，关系到低端机可用性）
5. **给 AsrClient / MicRecorder 补测试**（当前零覆盖，而这是核心链路）
6. **合并三份剪贴板列表 UI**（P8，长期维护成本）
7. **更新 AGENTS.md / HANDOFF.md**（消除误导）

### 10.4 如果服务端要改

协议权威在 `CapsWriter-Offline-master/core/protocol.py`。客户端对应关系：

| 服务端 | 客户端 |
|---|---|
| `AudioFormat.SAMPLE_RATE` | `Protocol.SAMPLE_RATE = 16000` |
| `config_client.py: mic_seg_duration` | `Protocol.SEG_DURATION = 60.0` |
| `config_client.py: mic_seg_overlap` | `Protocol.SEG_OVERLAP = 4.0` |
| `config_client.py: trash_punc` | `JinnIme.TRAILING_PUNC` |
| `engines/language.py` | `Prefs.language` |

`docs/` 目录按约定**只读、不推送 GitHub**，改服务端时不要顺手改它。

---

## 附：常用命令

```bash
# 单元测试（无需设备）
.\gradlew.bat testDebugUnitTest

# 构建
.\gradlew.bat assembleDebug              # → app/build/outputs/apk/debug/app-debug.apk
python build_apk.py                       # 一键签名打包 → ./jinn-release.apk
python build_apk.py --clean --install     # 清理 + 构建 + adb install

# 真机（设备 192.168.1.33:5555，KernelSU root）
adb install -r app-debug.apk
adb shell ime set com.jinn.inputmethod/.JinnIme
adb shell cat /storage/emulated/0/JinnIme/logs/jinn-*.log
adb shell dumpsys meminfo com.jinn.inputmethod

# root 环境下授权直写共享存储（日志才能落到 /storage/emulated/0/JinnIme/）
adb shell su -c 'appops set com.jinn.inputmethod MANAGE_EXTERNAL_STORAGE allow'

# 一键抓诊断包（日志 + logcat + dumpsys + 剪贴板 DB）
powershell -File tools/bug_capture.ps1
```
