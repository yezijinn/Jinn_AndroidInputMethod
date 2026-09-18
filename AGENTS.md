# 项目说明：Jinn 安卓输入法

安卓拼音输入法（IME）：26 键全拼 / 双拼（7 套方案，默认自然码）+ 剪贴板历史 + 分类词库，**全部本机运行**。

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
- 覆盖（26 个测试类 / 247 个用例）:
  - 近期改动的对拍/压测：`RecentChangesParityTest`（分词剪枝 ≡ 未剪枝参考实现 534 例、分片解压 ≡ readBytes
    逐字节一致、长按清拼音判据边界矩阵）、`SegmentCliffTest`（切不通的长拼音不再卡）、
    `KeyboardStressTest`（7 场景 1514 键逐键耗时 + 固定种子模糊测试 589 例）
  - 协议：`ProtocolTest`（序列化 / 解析）
  - 拼音引擎：`PinyinEngineTest` / `ShuangpinTest` / `PinyinCompletionTest`
  - 词库：`PhraseDictIntegrityTest`（词库完整性）、`RareCharsFilterTest`（生僻字过滤）、
    `OptionalDictMergeTest`（可选包合并去重）、`CandidateCountBoundTest`（候选数量边界）
  - 索引与两段式加载：`IndexParityTest`（索引 vs 文本逐键对拍 + 头部健壮性）、
    `IndexBuilderParityTest`（设备端构建器与 Python 脚本逐字节一致 + 可选索引合并语义）、
    `IndexFeatureRegressionTest`（残码消费 / 智能预测回归）、
    `HotDictAssetTest`（子集必是全量前缀）、`HotDictMergeTest`（子集先行 + 全量并入一致性）
  - 双拼方案：`ShuangpinSchemesTest`（7 套 + 全表往返自洽）、`HintRuleTest`（键面提示三规则）
  - 剪贴板：`ClipboardClassifierTest`、`ClipboardClassifierBoundaryTest`（分类边界，
    含 CRLF / 长度边界 / 负例）、`ClipboardDedupeTest`、`ClipboardFilterTest`
  - 文字拖选：`TextSelectionTest`
  - 键盘外观：`KeyAppearanceTest`（圆角 / 间隙的定义域、步进、钳位与进度换算）
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
├── ClipboardDb.kt           # 剪贴板历史 SQLite（AES-256-GCM 密文 + category/is_favorite + 去重/裁剪/搜索）
├── ClipboardCrypto.kt       # 加密工具（Android Keystore AES-256-GCM，base64(iv):base64(cipher)）
├── ClipboardClassifier.kt   # 自动分类（URL/NUMBER/OTHER，纯逻辑可单测；隐私绝不自动）
├── ClipboardPrefs.kt        # 剪贴板配置（独立 SharedPreferences：enabled/maxItems/root 增强）
├── ClipboardFirewall.kt    # Root 增强：数据目录安全审计（不做任何清空系统剪贴板操作）
├── ClipboardPanelView.kt   # 键盘内嵌剪贴板面板（列表 UI 与搜索面板重复，改一处要同步两处）
└── SearchPanelView.kt      # 顶部剪贴板搜索面板（剪贴板内容的第二个展示入口，同上）

> `ClipboardDb.kt` 内含顶层 `ClipboardFilter`：负责把分类栏的伪分类
> （FAVORITE，实为独立标签列）翻译成 SQL 参数。**收藏绝不能当
> category 传给 SQL**，否则列表恒空；该规则由 `ClipboardFilterTest` 守卫。
> （隐私功能已在 v5 迁移整体移除。）
```

## 按键/模式排查口径（重要）

- **按键日志自带当前模式**：`拼音输入[双拼·自然码]: jgh` / `拼音输入[全拼]: nihao` / `拼音输入[英文]: abc`。
  排查或做真机验证前，**先看这一条确定"现在是什么键盘"**——只看 composing 判不出模式，
  历史上因此连续踩坑（拿全拼的截图去证明双拼修复、在拼音串非空时点方案切换键点到候选）。
- **切换全拼/双拼**：功能面板只在**拼音串为空时**才渲染 → ① 先退格清空；② 点候选栏第 1 键；
  ③ 日志出现「输入方案切换: …」才算切成功。
- **界面默认值**：用户词频学习 **开**、候选预测词 **关**、剪贴板历史上限 **500**（`ClipboardPrefs`）。

## 诊断日志（重要）

- 输出目录（**首选**）：`/storage/emulated/0/JinnIme/logs/jinn-YYYY-MM-dd.log`（按天滚动，保留 7 天）
  · **本机实测（2026-09-17，KernelSU）该目录为空**：`Diagnostics.init()` 的 appops+chown 尝试在本机未生效，
    实际写入的是**降级目录** `Android/data/com.jinn.inputmethod/files/logs/`（或 `/sdcard/Android/data/...`）。
    排查时**两个目录都要看**，不要只按首选路径找。
- 无共享存储写权限时自动退回 `Android/data/<pkg>/files/logs/`
- 真机 root 环境（KernelSU）下先 `su -c 'appops set <pkg> MANAGE_EXTERNAL_STORAGE allow'`
  使应用可直写共享目录；应用侧 `Diagnostics.init()` 也会尝试 appops+chown，兜底 app 专属目录
- 崩溃自动写入 stack trace 并 dump `logcat-<ts>.log` 快照到同目录
- 所有关键路径已埋点（连接/重连/收发/录音/VAD/权限/剪贴板保存/词库加载），排查先用 `cat` 日志文件
- 排查命令：`adb shell cat /storage/emulated/0/JinnIme/logs/jinn-*.log`
- `Diagnostics.i/v/w/e` 同时写 logcat 与日志文件；`PinyinEngine` 加载完成会打
  「词库加载完成: 音节=N 词语键=N」与耗时（后台线程；高频子集 ~0.3s 即可打字，全量索引随后就绪——日常为**内存映射复用约 0.05s**，仅 App 更新后首次需解压重建约 1.8s）
- 可选词库为**延迟加载**：由三种空闲信号之一触发（息屏 / 键盘收起后闲置 20s / 兜底 180s，
  见 `JinnIme.maybeLoadOptionalDict`），因此「开机后首次输入的候选就绪」不被大词库拖慢
- 剪贴板保存打 `save: 已保存 … (分类=...)`；剪贴板页刷新打 `refresh: 全库=N 分类=... 查询=N`

## 词库来源与生成（重要）

- **源**：`docs/rime-ice/cn_dicts/{base,ext,tencent}.dict.yaml`（雾凇拼音，本机 `docs/` 内，不入库）。
- **生成**：`tools/dict_builder/`，细节见其 **`README.md`**（脚本一览 + 现行/历史划分）。
  `convert_rime_ice.py` 出基础包与扩展包，`convert_rime_tencent.py` 出腾讯可选包。
- **分层**：基础包（词长 ≤4 字含四字成语）随 APK；扩展包（>4 字）与可选包放 Release 附件，
  设置页「分类词库」按需下载到 `filesDir/dicts/`。
- **全量基础包以二进制索引发布**（`assets/pinyin_index.bin.xz`，由 `build_dict_index.py` 构建）：
  运行时只解压 + 读偏移数组 + 二分查找，不再解析文本、不再建 60 万级 HashMap
  （真机：基础索引日常 **0.05s**（内存映射）/ 首次 1.8s；进程 PSS 305MB → **77.7MB**（含可选包））。
  **生僻字过滤在查询期**（`phrasesFor`），因此索引可原样复用、与开关无关。
  ⚠ 反向索引（69 万条「词→拼音」）已移除，改由 `candidatePinyin`（查询时记录候选→键，上限 4096）
  支撑「残码保留」与「智能预测」——**改动这两处务必跑 `IndexFeatureRegressionTest`**。
  ⚠ **索引读取一律走内存映射**（2026-09-17，吸收 librime `Prism : MappedFile`）：
  `PhraseIndex` 内部持 `ByteBuffer`（堆内 `wrap` / 映射 `map`）。基础索引因资产是 xz，
  改为「**解压一次 → 原子落盘 `filesDir/index/base.<APK mtime>.idx` → 映射**」；
  可选包缓存 `filesDir/index/<包名>.idx` 同样映射。**缓存写入必须「临时文件 + 原子改名」**
  （直接覆盖会截断正在使用的映射，Linux 上 SIGBUS）。
  ⚠ **两类缓存的清扫判据要分开**：可选包的"清理失效缓存"只能删 `<包名>.idx`，
  **绝不能碰 `base.` 前缀**（其源是 APK 内资产、不在包列表里）。判据已抽成纯函数
  `PinyinEngine.staleOptionalCacheNames()`，由 `IndexCacheLifecycleTest` 守卫——
  历史上这里出过一次严重缺陷：每次可选包加载都把基础缓存删掉，导致内存映射永远命中不了。
  ⚠ **索引格式 = v2（长度数组）**：`keysBlob + keyLengths(u8) + wordsBlob + wordLengths(u16)`。
  改格式必须**同时**改构建脚本与设备端构建器（`PhraseIndex.build`），并跑
  `IndexBuilderParityTest`（逐字节对拍）；版本号一升，设备上的旧缓存会自动重建，无需用户操作。
- **改完词库必做**：`convert_rime_ice.py` 出文本留档 → `build_dict_index.py` 出索引资产
  （`noCompress += "xz"` 不可删）→
  真机验证加载与输入 → 扩展包/可选包重传两端 Release 并实测下载（比对 sha256）。
- ⚠ **旧源文件 `tools/dict_builder/source/pinyin_phrases.txt` 已于 2026-09-17 删除**
  （29.4MB、含 573 行 GBK 乱码、被 `.gitignore` 忽略、从未进版本库）。
  **不要再把词库源文件放回这个目录**——现行源在 `docs/rime-ice/`，重跑旧文件只会把乱码带回词库。

## 用户词频学习（UserFrequency）

- 语义对齐 librime `UserDictionary`/`UserDb`：记录「用户**明确选过**的候选」，按权重稳定排序提到前面。
  累加公式取自 `algo/dynamics.h` 的 `formula_d`：`dee_new = commits + dee_old * exp((tick_old - tick_now) / 200)`
  （tick = 天计数，半衰期 ≈ 139 天）；当天重复选择不加衰减。
- **只在「点候选栏 / 空格取首候选 / 点预测词」时学习**；`commitComposing`（收起键盘自动上屏）、
  语音结果、剪贴板粘贴**都不学**——那些不是用户选择。
- 存储 `filesDir/user_freq.txt`（`词<TAB>权重<TAB>天`），**原子写**（临时文件 + 改名）且**防抖 2s**、
  退出时 `PinyinEngine.flushUserFrequency()` 兜底；上限 3000 条、权重 < 0.15 丢弃。
- 排序是**稳定排序**且未学习时直接返回原数组 ⇒ 对没学过的候选零影响（不打乱词库既有手感）。
  调 `UserFrequency.rank()` 的位置在 `PinyinEngine.query` 的收尾处。
- 开关：设置页「用户词频学习」（`Prefs.userLearning`，默认开，**立即生效**，本地存储不上传）。
- 护栏：`UserFrequencyTest`（排序稳定性 / 衰减 / 序列化往返 / 脏数据容错 / 开关 / 原子写）。
  ⚠ 改这块务必跑该测试 + `PinyinEngineTest`（排序相关）。

## 冷启动与加载顺序（重要）

- **词库是两段式加载**（2026-09-17 起）：
  1. `assets/hot_phrases.txt.xz`（高频子集，4 万词 / 220KB）→ **真机 ~0.28~0.44s 即可输入**（全新安装后首次启动偏慢，实测 442ms）；
  2. `assets/pinyin_index.bin.xz`（全量基础包**二进制索引 v2**，60.4 万键）→ 解压 + 读长度数组
     + 二分查找（真机索引 1.70s / 两段式总计 2.31s；文本资产已不进 APK）。
  - `PinyinEngine.isLoaded` = 可以打字了（第一段完成）；`isFullyLoaded` = 候选已全量。
  - ⚠ **改词库必须同时重跑 `python tools/dict_builder/gen_hot_dict.py`**：子集必须是全量
    每个键的**前缀**，否则并入后候选顺序会与全量单载不一致（`HotDictAssetTest` 会直接失败）。
  - ⚠ **任何新增的启动期任务都必须显式降优先级**
    （`Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND)`），不要与词库加载抢 CPU
    （双拼键位表预热、可选包加载都已按此处理）。
  - 加载期反馈：候选栏会显示「词库加载中…」（未就绪）与「词库补全中…」（已有词库但查不到候选
    且全量尚未并入），文案在 `strings.xml`（IME 内禁弹窗）。
  - **可选词库包已索引化（Stage 2）**：首次加载时由 `PhraseIndex.build` **流式**构建索引并落盘
    `filesDir/index/<源文件>.idx`，之后启动直接读缓存（源 `length:mtime` 摘要校验，变过才重建；
    源包删除则启动时清理缓存）。查询是「运行时 → 基础索引 → 可选索引」**逐段查找 + 去重合并**
    （`mergedCache`，**任何词库变更都必须让该缓存失效**——`loadPhrasesReader` 里已 clear）。
    改这块必须跑 `IndexBuilderParityTest`（构建器字节级一致 + 合并语义）。
  - **可选词库包只在空闲时加载**，禁止改回定时加载：
    三种信号先到先得（息屏 / 键盘收起后闲置 20s / 兜底 180s），实现见 `JinnIme.maybeLoadOptionalDict`；
    首次加载会为每个包构建索引并落盘（见上一段），之后启动直接读缓存（真机 8.7s → 44ms）。
- 冷启动阶段的耗时构成与逐条优化方案见 `docs/冷启动卡顿-诊断与优化方案.md`
  （P0-1 高频子集先行 / P0-2 加载期提示 / P1 持久化二进制索引）。
- 内存实测口径：`su -c 'cat /proc/<pid>/smaps_rollup'` 看 PSS 与 Private_Dirty
  （`dumpsys meminfo` 在本机 ROM 上会 IoException 超时）。

## 双拼方案（键位数据是生成的，禁止手改）

- 7 套方案：全拼 / 自然码 / 小鹤 / 搜狗 / 微软 / 紫光 / 智能ABC / 加加，键位数据在
  **代数语义已与 librime C++ 逐条核对**（2026-09-17，核对源 `docs/librime-master/src/rime/algo/{calculus.h,calculus.cc,algebra.cc}`
  —— 该目录**不入库、不影响构建**，纯参考）：xform=**替换**（默认 addition+deletion 均 true）、derive=**保留+追加**（deletion=false）、
  erase=**整串匹配（boost::regex_match）才清除**、xlit 要求两侧**字符数相等**、每个 op 作用于**上一轮整个拼写集合**（链式组合）。
  逐条对照与本次修正的 4 处潜伏差异见 `gen_shuangpin_tables.py` 头部注释；改代数实现须重跑生成器 +
  `verify_shuangpin_migration.py` + `testDebugUnitTest`。
  `ShuangpinSchemes.kt`（**按方案惰性构建**，见 `Shuangpin.warmUpAll` 的后台预热），**由 `tools/dict_builder/gen_shuangpin_tables.py` 从
  `docs/rime-ice/double_pinyin*.schema.yaml` 的 `speller/algebra` 生成**（按 librime 代数语义
  施加到 422 个合法音节）。**要改键位就改 schema 或生成器里的 `SCHEMES`，不要改生成文件。**
- **键面提示的硬性规则（用户明示，禁止违反）**：① **绝不显示与该键字母相同的韵母**
  （e 键不显示 e、v 键不显示 v、a/i/u 同理）；② ü 的两种写法同时出现只留 `v`；
  ③ **总行数强制 ≤ 2**（含红色 zh/ch/sh 那一行）：该键承担 zh/ch/sh 时韵母挤一行、
  红色占第 2 行；④ 同一行内按「长者在前、同长按韵母表规范顺序」排序。
  规则实现在生成器模板的 `ShuangpinTable.finalHint`，由 `HintRuleTest`（7 套 × 26 键全量扫描 +
  用户点名项）守卫——**改提示逻辑必须同时改生成器并重跑该测试**。
- **键面提示（字母键下的韵母 + zh/ch/sh 红字）由码表在运行期反推**
  （`ShuangpinTable.finalHint` / `initialOf`）——**不要再手写一份键位提示表**：
  历史上那份手写表把 `o` 键写成 `ou`（实际 `o/uo`，`ou` 在 `b` 键），直接骗用户。
- **分号键**：搜狗 / 微软 / 紫光的 `ing` 落在 `;` 上，`key_semicolon` 只在
  `Shuangpin.needsSemicolon(scheme)` 为真时显示（其余方案 GONE，不参与测量，26 键布局零影响）。
- **键盘功能面板的「全拼 / 双拼」按钮 = 原来的二态开关，禁止改文案与交互**
  （用户明示）：主文本 `全拼`/`双拼`、副文本 `换双拼`/`换全拼`，**不得显示具体方案名**；
  它只切「用不用双拼」（`ShuangpinScheme.toggle`），切回时取设置里选定的那套。
  **具体方案只在设置页「双拼的输入方案」下拉里改**——禁止把方案选择放进输入法面板；
  该下拉**只列 7 套双拼、不含「全拼」**（列表取自 `ShuangpinScheme.SHUANGPIN_ONLY`），
  选中即「记住 + 启用双拼」；关闭双拼用面板按钮。
- **方案持久化**：`Prefs.useShuangpin`（Boolean，面板按钮写）+ `Prefs.shuangpinScheme`
  （Int 1..7，设置页写，**只增不改**）+ `Prefs.effectiveShuangpinScheme`（生效方案，IME 读）。
  老键 `shuangpin` 语义不变（面板开关），无需迁移。
- **Spinner 写配置必须「用户触摸过」才允许**（`setOnTouchListener` 置位闸门，见
  `SettingsActivity` 的两个下拉）：只用「初始化完成」标志挡不住 `onRestoreInstanceState`
  触发的迟来 `onItemSelected`，会静默改写用户配置（真机实测踩到过）。
- 改键位/加方案后必须：`.\gradlew.bat testDebugUnitTest`（`ShuangpinTest` 66 例是自然码回归基线，
  `ShuangpinSchemesTest` 覆盖 7 套 + 全表往返自洽）＋ `tools/dict_builder/verify_shuangpin_migration.py`
  对拍（换表不许改行为）。
- 已知边界（不修，属模型固有取舍）：`hng/hm/m/n`（叹词）与 `junding`（音节表脏数据）无法编码；
  同码写法让位（`lo`→`luo`、`lve/nve`→`lue/nue`、`ng`→`neng/nang/niang`），
  其中 `lve/nve` 词条仍可命中（查询链路有 ue↔ve 变体回退）。

## 约定

- **禁止修改语音部分**：`MicRecorder`、`AsrClient`、`Protocol`、`MicButton`，以及 `JinnIme`
  中的录音、WebSocket 连接、识别结果处理链路，均为作者自用，**不得改动**——语音功能的
  外部可用性不在考虑范围内。若某问题必须改语音才能修，只登记不修并说明原因。
- 提交风格：中文短语，`动词 + 对象`（如「修复设置页布局崩溃」）
- 单模块 `:app`，无多模块拆分；不改协议字段名（服务端 from_dict 严格校验）
- 通信帧：一次听写 = 若干 `is_final=false` 音频包 + 一个 `data=""` 的 `is_final=true` 收尾包；
  服务端返回的是**整段累积文本**，客户端整体覆盖显示，绝不自行拼接；取消也发收尾包按 taskId 丢弃
- 字体渲染、键盘布局改动需在真机截图确认（编译期无感，只有真机才看得出）
- **键盘外观参数**：26 键区（3 行 28 键）的圆角与间隙由设置页的 `Prefs.keyCornerDp` /
  `Prefs.keyGapDp` 控制，定义域与换算**只允许**写在 `KeyAppearance.kt`
  （圆角 0~24dp、间隙 0~8dp，默认 0dp/0dp；间隙 = 相邻两键之间的空隙，四边各内缩一半）。
  26 个字母键走 `PinyinKey.setKeyAppearance` 自绘，大写/删除键由
  `PinyinKeyboardView.applyKeyAppearance` 动态重建背景 + 设四周外边距 ——
  **任何一处都不许再写死圆角/间距**，否则同一行会错位。
  参数在 `onStartInputView → configure()` 时重套，改完收起再弹出键盘即生效。
- `docs/` 是**本地历史资料目录**（调研、设计稿、排查记录、接手指南等 90+ 文件），
  **不推送 GitHub**、不需要处理/完善，保持只读。
  **新增这类资料请直接放 `docs/`，不要放根目录**（`.gitignore` 已忽略 docs/ 与 release/）。
- **根目录只保留 4 个文档**：`README.md`（中文，主）/ `README_EN.md`（英文）/
  `AGENTS.md`（本文件）/ `更新日志.md`（按轮次记录）。其余历史文档一律归档到 `docs/`。

### 剪贴板模块关键约束

- **第三方 APP 不得读取 History**：JinnIme 不提供任何 History API（ClipboardHistoryProvider/PermissionStore/PermissionActivity 已全部移除）。第三方 APP 只能读取 Android System Clipboard 的最新内容，无法访问 JinnIme 私有历史库
- **不干预 System Clipboard**：JinnIme 不阻断、篡改或周期性清空 Android System Clipboard；网盘、购物、分享类 APP 识别口令/链接的功能不受影响
- **入库去重**：每条内容写入时按 `content_hash` 唯一约束原子 upsert——同内容已存在则更新元数据并置顶（不动收藏/隐私标记），不存在则 INSERT。**禁止依赖「打开面板时全表 deduplicate」维持数据正确性**（面板打开只负责读取快照渲染）
- **分类**：固定 **全部 / 网址 / 数字 / 收藏**；收藏是独立标签，可与分类并存。
  隐私分类与隐私标记入口已移除（2026-09-16）：有收藏即可满足置顶需求，隐私冗余。
  `is_private` 列**已在 v5 迁移中删除**，掩码逻辑随之失效（隐私功能整体移除，不做兼容）。
- **序号**：UI 序号非 DB ID，最新=最大，删除/去重后重新连续编号；搜索保留原始序号
- **点击粘贴**：面板点击条目 → 广播（`ACTION_CLIPBOARD_PASTE`）回传 IME → `commitText`；
  连接无效时暂存 `pendingPasteText`，`onStartInputView` 时自动提交；无效连接不崩溃
- **ownCommit 陷阱**：`onOwnCommit()` 只在真正写系统剪贴板的粘贴路径调用；打字/语音上屏走 `commitText`
  不写系统剪贴板，**严禁**在 `commit()` 里调 `onOwnCommit()`——否则标记残留会把用户真实复制误杀
- **加密**：正文 AES-256-GCM（Android Keystore），`base64(iv):base64(cipher)`；绝不落明文日志