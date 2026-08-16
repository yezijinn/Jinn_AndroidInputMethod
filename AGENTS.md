# 项目说明：CapsWriterIME

安卓极简语音输入法（IME）。采集麦克风音频经 WebSocket 实时上传到飞牛 NAS 上的
CapsWriter Offline 服务端（`ws://<host>:6016`，子协议 `binary`）识别，本机零模型。
配套服务端源码在本仓库 `CapsWriter-Offline-master/`（协议对齐 `core/protocol.py`）。

## 技术栈

| 层 | 技术 |
|---|---|
| 语言 | Kotlin（官方风格） |
| 构建 | AGP 8.5.2 + Kotlin 1.9.24，Gradle Wrapper 8.9（本机用本地 8.10.2 等效） |
| JDK | 17（compileOptions 与 jvmTarget 均 17） |
| SDK | compileSdk 34 / minSdk 26 / targetSdk 34（本机另有 android-36 可用） |
| 依赖 | 仅 core-ktx、activity-ktx、okhttp 4.12、shizuku-api（可选）、junit+org.json（测试） |

## 代码风格

- 文件命名：`PascalCase.kt`，与类名一致；资源 `snake_case`
- 每个类带 KDoc 注释说明职责与关键约束（协议、线程模型、生命周期）
- 回调线程语义必须在注释标注（OkHttp IO 线程 / 采集线程 / 主线程），接收方自行切主线程
- 错误处理：`runCatching` + `onFailure` 打日志后回退，绝不静默吞异常；不抛受检异常
- 配置一律走 `Prefs`（SharedPreferences），不散落魔法值；协议常量收敛在 `Protocol`/`AudioMessage`
- 空安全优先：可空返回用 `?:` 给默认值，`!!` 仅用于必然非空且带注释处

## 测试

- 运行：`& "$env:LOCALAPPDATA\Temp\opencode\gradle-8.10.2\bin\gradle.bat" testDebugUnitTest`
- 模式：`app/src/test/java/...`，JVM 单测（JUnit 4），无需设备
- 覆盖：协议序列化/解析（ProtocolTest.kt）、拼音引擎（PinyinEngineTest/ShuangpinTest）、
  敏感内容检测（SensitiveDetectorTest）、文字拖选（TextSelectionTest）、剪贴板自动分类（ClipboardClassifierTest）
- 注意：测试 KDoc 注释里禁止出现 `*/`（会提前终止块注释导致编译失败）

## 构建与运行

- Debug：`gradle.bat assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Release：`gradle.bat assembleRelease` → `app/build/outputs/apk/release/app-release-unsigned.apk`（未签名）
- 真机安装：`adb install -r app-debug.apk`，设备 `192.168.1.33:5555`（KernelSU root）
- 真机输入法：`adb shell ime set com.jinn.voiceinput/.JinnIme`（实际包名是 `com.jinn.voiceinput`，非 `com.capswriter.ime`）
- 本机 SDK：`C:\Android\sdk`（local.properties 已写 sdk.dir），JDK17 在 PATH

## 项目结构

```
app/src/main/java/com/jinn/voiceinput/
├── Protocol.kt         # 协议常量 + AudioMessage(发) / RecognitionMessage(收) 序列化
├── Prefs.kt            # SharedPreferences 配置（host/port/language/prompt/保活开关）
├── MicRecorder.kt      # 16kHz PCM16 采集 → float32 小端；本地 VAD 静音检测
├── AsrClient.kt        # OkHttp WebSocket：beginTask/sendChunk/endTask + 断线指数退避重连
├── MicButton.kt        # 麦克风按钮（纯绘制，手势判定在 IME）
├── JinnIme.kt          # 输入法服务：语音键盘 + 拼音键盘双模式、长按/短按手势、结果回显、保活拉起
├── PinyinEngine.kt     # 拼音引擎：词库加载（HashMap 预分配 + BufferedReader 流式）、候选查询、自然码双拼
├── PinyinKeyboardView.kt # 拼音键盘视图：26 键 QWERTY + 候选栏 + 功能面板（全拼/剪贴板/方向/粘贴/收起）+ 智能预测
├── PinyinKey.kt        # 拼音键盘单键（纯绘制：字母 + 双拼韵母/声母提示）
├── TextSelection.kt    # 文字拖选核心逻辑（Anchor/Focus 模型，纯函数可单测）
├── KeepAliveService.kt # 前台保活服务（常驻通知，防掉后台）
├── JinnAccessibilityService.kt # 无障碍互保 + 输入场景监测（5s 节流）
├── BootReceiver.kt     # 开机/更新后拉起保活
├── RootShizuku.kt      # Root(su)/Shizuku 加白名单（Shizuku.newProcess 用反射，13.x 已私有）
├── SettingsActivity.kt # 设置页（服务端/语言/提示词/授权/保活/剪贴板）+ 保存并测试连接
├── Diagnostics.kt      # 诊断日志：文件输出 + 崩溃捕获 + logcat 快照
│
├── ClipboardController.kt   # 剪贴板监听（PrimaryClipChangedListener）+ 敏感策略 + 保存到库
├── ClipboardStore.kt(内)    # ClipboardController 内 object：敏感检测→分类→加密入库纯逻辑
├── ClipboardDb.kt           # 剪贴板历史 SQLite（AES-256-GCM 密文 + category/is_favorite/is_private + 去重/裁剪/搜索）
├── ClipboardCrypto.kt       # 加密工具（Android Keystore AES-256-GCM，base64(iv):base64(cipher)）
├── ClipboardClassifier.kt   # 自动分类（URL/NUMBER/OTHER，纯逻辑可单测；隐私绝不自动）
├── ClipboardPrefs.kt        # 剪贴板配置（独立 SharedPreferences：enabled/maxItems/敏感策略/root 增强）
├── ClipboardHistoryActivity.kt # 剪贴板历史页（全新 Activity：分类栏/动态序号/实时搜索/去重/长按菜单/点击粘贴广播回传）
├── ClipboardPermissionActivity.kt # 第三方 APP 读取授权管理页
├── ClipboardPermissionStore.kt    # 授权表（默认全禁，按包名授权，每次限 3 条）
├── ClipboardHistoryProvider.kt    # 第三方 APP 安全 IPC（ContentProvider，Binder 校验调用方）
├── ClipboardFirewall.kt    # Root 增强：敏感内容自动清空系统剪贴板 / 定时清空
└── SensitiveDetector.kt    # 敏感内容检测（密码/验证码/身份证/银行卡/Token/JWT/Cookie/私钥，纯逻辑可单测）
```

## 诊断日志（重要）

- 输出目录：`/storage/emulated/0/JinnIme/logs/jinn-YYYY-MM-dd.log`（按天滚动，保留 7 天）
- 无共享存储写权限时自动退回 `Android/data/<pkg>/files/logs/`
- 真机 root 环境（KernelSU）下先 `su -c 'appops set <pkg> MANAGE_EXTERNAL_STORAGE allow'`
  使应用可直写共享目录；应用侧 `Diagnostics.init()` 也会尝试 appops+chown，兜底 app 专属目录
- 崩溃自动写入 stack trace 并 dump `logcat-<ts>.log` 快照到同目录
- 所有关键路径已埋点（连接/重连/收发/录音/VAD/保活/权限/剪贴板保存），排查先用 `cat` 日志文件
- 排查命令：`adb shell cat /storage/emulated/0/JinnIme/logs/jinn-*.log`
- `Diagnostics.i/v/w/e` 同时写 logcat 与日志文件；`PinyinEngine` 加载完成会打
  「词库加载完成: 音节=N 词语键=N」与耗时（后台线程，约 7s，不阻塞 UI）
- 剪贴板保存打 `save: 已保存 … (分类=...)`；剪贴板页刷新打 `refresh: 全库=N 分类=... 查询=N`

## 约定

- 提交风格：中文短语，`动词 + 对象`（如「修复设置页布局崩溃」）
- 单模块 `:app`，无多模块拆分；不改协议字段名（服务端 from_dict 严格校验）
- 通信帧：一次听写 = 若干 `is_final=false` 音频包 + 一个 `data=""` 的 `is_final=true` 收尾包；
  服务端返回的是**整段累积文本**，客户端整体覆盖显示，绝不自行拼接；取消也发收尾包按 taskId 丢弃
- 保活相关（前台服务/无障碍/白名单）改动需在真机验证，厂商杀进程行为不可模拟

### 剪贴板模块关键约束

- **分类**：固定 全部/网址/隐私/数字/收藏；隐私**只能用户手动标记**，绝不自动；收藏是独立标签可与分类并存
- **序号**：UI 序号非 DB ID，最新=最大，删除/去重后重新连续编号；搜索保留原始序号
- **点击粘贴**：Activity 无法直接拿 InputConnection → 广播（`ACTION_CLIPBOARD_PASTE`）回传 IME → `commitText`；
  连接无效时暂存 `pendingPasteText`，`onStartInputView` 时自动提交；无效连接不崩溃
- **ownCommit 陷阱**：`onOwnCommit()` 只在真正写系统剪贴板的粘贴路径调用；打字/语音上屏走 `commitText`
  不写系统剪贴板，**严禁**在 `commit()` 里调 `onOwnCommit()`——否则标记残留会把用户真实复制误杀
- **保存竞态**：复制→保存是后台线程（AES 加密耗时），页面打开时可能读到旧状态；
  剪贴板页 `onResume` 分时延迟刷新（300ms/1s/2.5s）兜底
- **空列表越界**：空分类（如隐私/收藏无记录）时 ListView 已 GONE，`setSelection(0)` 会抛
  `IndexOutOfBoundsException`；滚回顶部必须 `currentItems.isNotEmpty()` 判空
- **滚动残留**：ListView 保持上次滚动位置，分类切换/去重/删除后必须滚回顶部，否则切回长列表只见底部几条
- **加密**：正文 AES-256-GCM（Android Keystore），`base64(iv):base64(cipher)`；绝不落明文日志
- **隐私安全**：剪贴板页 `FLAG_SECURE` 禁止截图；隐私内容默认隐藏明文，点击一次才显示再点才粘贴
