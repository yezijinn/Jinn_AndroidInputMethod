# CapsWriter 语音输入法（安卓）

基于飞牛 NAS 上已运行的 **CapsWriter Offline** 服务端、极度精简的安卓语音输入法（IME）。
本机只负责采集麦克风音频并通过 WebSocket 实时上传，识别完全在 NAS 服务端完成。

## 核心特性

- **只做语音听写**：键盘只有一个麦克风 + 一排最小编辑键（退格 / 回车 / 空格 / 逗号 / 句号 / 切换输入法）。
- **两种触发方式**
  - 长按麦克风：按住说话，松手即识别；按住时**上滑**再松手可取消。
  - 短按麦克风：进入连续录音，再点一下结束（最长 3 分钟兜底自动停止）。
- **实时回显**：识别过程用预编辑文本（composing）即时显示，完成自动落地；兼容性差的输入框可关闭。
- **零额外模型**：不打包任何语音模型，包体极小，仅依赖 `core-ktx`、`activity-ktx`、`okhttp3`，可选 `Shizuku`。
- **后台保活 / 防杀后台**：前台常驻服务 + 无障碍互保 + 开机自启；可选 Root/Shizuku 把本包加入系统白名单，降低被回收概率，识别连接更稳。详见下文。

## 对接飞牛服务端

服务端即你已在飞牛 Docker 跑的 CapsWriter Offline，端口 `6016`。

| 项 | 值 |
| --- | --- |
| 地址 | 飞牛 NAS 局域网地址，如 `192.168.1.3` |
| 端口 | `6016` |
| 协议 | `ws://<host>:6016` |
| 子协议 | `binary`（请求头 `Sec-WebSocket-Protocol: binary`） |
| 音频格式 | 16kHz / 单声道 / float32 小端裸样本（Base64） |
| 分段参数 | `seg_duration=60`、`seg_overlap=4`（对齐桌面端 `config_client.py`） |

通信严格对齐服务端 `core/protocol.py`：一次听写由若干 `is_final=false` 的音频包加一个 `data` 为空的 `is_final=true` 收尾包组成；
服务端返回的 `text` 是**整段累积文本**，客户端直接整体覆盖显示，不自行拼接。取消也发收尾包以清空服务端按连接持有的音频缓冲，结果按 `taskId` 丢弃。

## 后台保活与防杀后台

语音识别依赖到飞牛服务端的常驻 WebSocket 连接，连接一旦被系统回收就得重连、可能丢掉在途结果。
为此提供三层保活，按需开启（均在设置页「保活与防杀后台」卡片）：

1. **前台保活服务**（`KeepAliveService`）：开启后常驻一条低优先级通知，把进程优先级抬到前台，降低被杀概率；服务用 `START_STICKY` + `onTaskRemoved` 自愈。
2. **无障碍互保**（`CapsWriterAccessibilityService`）：在系统「无障碍」里启用后，服务被系统托管常驻，其 `onServiceConnected` 会拉起前台服务；前台服务被杀时由无障碍再次拉起，互保。
3. **Root / Shizuku 加白名单**（`RootShizuku`）：开启「用 Root / Shizuku 加白名单」后点「运行 Root / Shizuku 防杀后台」，把本包加入 Doze 白名单并允许后台运行。优先走 Shizuku（免 root），未授权则退回 `su`。

此外「加入电池白名单（免优化）」可一键跳转系统电池设置，把本应用设为「不限制」。

> 说明：保活只能降低概率，无法 100% 阻止厂商激进杀进程；配合系统「允许自启动 / 锁定后台」开关效果最佳。

## 连接稳健性增强

除保活外，输入法还在连接与录音层面做了多重增强，进一步贴合用户预期：

- **断线自动重连**（`AsrClient`）：连接进入离线后按指数退避（1s 起、上限 30s）在主线程调度重连，连上即重置；仅主动关闭连接（如退出输入法）才停止重连，避免空转。
- **音频焦点管理**：录音前请求 `AUDIOFOCUS_GAIN`，来电或别的应用开始播音导致焦点被抢时自动停止当前录音，避免串音与麦克风冲突。
- **网络切换重连**：注册 `ConnectivityManager` 网络回调，WiFi↔热点切换导致 IP 变化、网络恢复可用时主动重连。
- **本地静音抑制（VAD）**（`MicRecorder`）：实时检测音量，连续静音超过 3 秒时——连续录音模式自动收尾出结果、长按说话模式仅提示「没听到声音」，减少无效上传。
- **权限请求现代化**：设置页改用 Activity Result API（`registerForActivityResult`）替代已弃用的 `requestPermissions`，`SettingsActivity` 继承 `ComponentActivity`。
- **无障碍真实用途**（`CapsWriterAccessibilityService`）：监测用户进入文本输入框（聊天 / 记事 / 搜索）时维持语音服务常驻，给出无障碍服务存在的合理依据，并以 5s 节流避免频繁拉起，降低被系统判定「滥用无障碍」而受限的风险。

## 工程结构

```
CapsWriterIME/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/   # Gradle 8.9 Wrapper（已内置，开箱即用）
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/capswriter/ime/
        │   ├── Protocol.kt        # 协议常量与报文序列化
        │   ├── Prefs.kt           # SharedPreferences 配置（含保活开关）
        │   ├── MicRecorder.kt     # 16kHz PCM16 采集 + float32 转换
        │   ├── AsrClient.kt       # OkHttp WebSocket 客户端
        │   ├── MicButton.kt       # 麦克风按钮（径向渐变 + 随音量呼吸的光圈）
        │   ├── CapsWriterIme.kt   # 输入法服务（手势 / 结果回显 / 编辑键 / 保活拉起）
        │   ├── KeepAliveService.kt# 前台保活服务（常驻通知，防掉后台）
        │   ├── CapsWriterAccessibilityService.kt # 无障碍保活（与前台服务互保）
        │   ├── BootReceiver.kt    # 开机 / 更新后拉起保活
        │   ├── RootShizuku.kt     # Root / Shizuku 加白名单工具
        │   └── SettingsActivity.kt# 设置页（服务端 / 语言 / 提示词 / 授权 / 保活）
        ├── res/
        │   ├── layout/  keyboard.xml, activity_settings.xml
        │   ├── xml/     method.xml, accessibility.xml
        │   ├── drawable/, values/, mipmap-anydpi-v26/
        └── res/values/  strings.xml, colors.xml, themes.xml, styles.xml
```

## 构建要求

- Android Studio（或命令行 Gradle）
- **Android SDK Platform 34** 与构建工具（本机当前未安装 SDK，故未做实际编译验证，代码已逐文件人工核对）
- JDK 17
- 网络可访问 Maven 中央仓库（或 `settings.gradle.kts` 中已注释的阿里云镜像）

### 构建步骤

```bash
# 方法一：Android Studio 直接打开 CapsWriterIME 目录，等待同步后 Build → Build Bundle(s) / APK(s)
# 方法二：命令行（项目已内置 Gradle 8.9 Wrapper）
cd CapsWriterIME
./gradlew assembleRelease            # 产物在 app/build/outputs/apk/release/
```

## 安装与启用

1. 把 APK 装到手机：`adb install app/build/outputs/apk/release/app-release.apk`。
2. 打开 App（或系统「设置 → 系统 → 语言与输入法 → 键盘/输入法」），点「在系统设置中启用本输入法」并把 CapsWriter 语音输入打开。
3. 在任意输入框调出键盘，点状态栏右侧「设置」或在桌面打开 App，填好飞牛 NAS 地址与端口，点「保存并测试连接」。
4. 点「切换到本输入法」或长按输入框选择 CapsWriter 语音输入。

## 使用

- **按住麦克风**说话，松手即识别；想反悔就**上滑**再松手取消。
- **点一下麦克风**进入连续录音，再点一下结束；长时间不点会自动停止。
- 纯语音改不了错字，用底部编辑键（退格支持长按连删；回车在聊天框会按输入框声明的动作发送）。

## 排错

| 现象 | 排查 |
| --- | --- |
| 提示「服务端未连接」 | 手机与飞牛在同一局域网；NAS 的 `6016` 端口已映射且 CapsWriter Offline 正在运行；地址填对 |
| 连不上 / 超时 | 检查飞牛容器端口转发；手机是否走了代理（`AsrClient` 已强制 `NO_PROXY` 直连） |
| 录音无反应 | 在设置页点「授权麦克风」，或系统设置里给本应用麦克风权限 |
| 识别乱码 / 语种不对 | 在设置页把语言从「自动」改为「中文」或对应语种 |
| 预编辑文本不显示 / 跳变 | 关闭「识别过程中实时回显」，改为完成一次性提交 |

## 环境说明

本工程源码已按 Android 规范完成并人工逐文件核对，但因构建环境缺少 Android SDK，未能执行 `assembleRelease` 实测编译。
首次在你本机打开时建议用 Android Studio 同步，按上方构建步骤产出 APK 后再安装。
