# Jinn 拼音输入法

轻量安卓输入法（IME）：26 键全拼 / 双拼（7 套）、剪贴板历史、分类词库。核心功能全部本机运行，无服务端；
APK 约 2.8MB，不含离线语音数据。

[English](README_EN.md) ｜ 中文

> **语音听写为可选、不开箱即用**：识别在服务端完成，需自行在家用 NAS 上部署
> [CapsWriter Offline](https://github.com/HaujetZhao/CapsWriter-Offline)（Docker），Android 端经 WebSocket 上传音频。
> 没有自建服务端就用不了。拼音键盘 / 剪贴板 / 词库与语音无关，无需服务端。

## 功能

**拼音键盘（26 键）**
- 全拼 / 双拼 / 英文；双拼 7 套（自然码、小鹤、搜狗、微软、紫光、智能ABC、加加），键面自动提示韵母与 zh/ch/sh（设置页「键盘内显韵母」可关，关闭后键面只显示字母）；设置页「拼音输入方案」切换，选中即全局生效
- 字符集：默认收录常用字 5,613 个（囧 / 淼 / 喆 / 昇 这类人名地名用字直接打得出）；设置页「加更多生僻字」进页可按档放开二级（837 字）与三级（2,923 字）生僻字，档 3 需先开档 2，勾选即时生效
- 「只使用繁体字」（设置页）：候选按简繁对照表替换为繁体上屏（如 爱 → 愛、碍 → 礙），开关即时生效；学习记录仍按简体保存，繁简两种模式共享
- 不完整拼音补全（`ni m` / `nim` →「你们」）、词库约束分词（`xuni` → `xu+ni` 虚拟）
- 候选栏：拼音条横排（显示按下的字母，双拼可按设置页「双拼候选全音」切成声韵全拼，如 `vsgo` → `zhongguo`），候选在其下；可选单行 / 双行，双行时拼音条夹在两排候选之间；候选栏追加候选词（默认关）；中英一键切换
- 圆角 / 间隙 / 透明度在设置页「键盘配色皮肤」调（0~24dp / 0~8dp / 0~100%），松手即时生效
- 键盘皮肤：32 套（原黑 / 原白 + 30 套配色），亮色 16 / 暗色 16，纯代码绘制零位图，与上面三项独立
- 界面明暗切换：跟随系统 / 亮色 / 暗色 / 定时；亮色与暗色**各选定一套皮肤**，切换时键盘皮肤随之切换
- 模糊音容错（默认关）：11 组口音等价类（zh⇄z、n⇄l、en⇄eng 等）按需勾选，命中不到时补充该音的其他读法（精确候选一个不动、不被替换）
- 删除键：单击删 1 字、按住连删、双击后按住连输入框文字一起清；候选栏 ✕ 一键清空当前输入

**剪贴板历史**（输入法内嵌面板）
- 复制自动保存，AES-256-GCM 加密存本机私有库，仅本输入法可读
- 分类：全部 / 网址 / 数字 / 收藏；动态序号、点击即粘贴、长按收藏或删除、清空需二次确认且不动收藏
- 顶部搜索面板：关键词实时检索，点击直接上屏

**词库与学习**
- 分类词库（设置页「补充短语词库」）：内置 40 万条，另提供三个下载包 —— 2级词库+40万（建议下载）/ 3级词库+50万 / 4级词库+60万；空闲时后台加载，下载后自动重启输入法
- 用户词频学习：记住点过的候选并排到前面；数据只留本机，可关

**在线翻译**（可选，自带 Key）
- 一键翻译：**选中文字就翻选中的那一段、译文原地替换**；没选中则取光标前后（范围可选），译文追加在原文下一行。候选栏功能面板第 6 键（关掉即不出现）
- 六家服务方：阿里云 / Azure / 百度 / 百度大模型 / DeepL / OpenAI 兼容；默认服务方，以及「翻译原文范围」（光标前本行 / 所在整行 / 光标前全部 / 编辑框全部）按服务方独立配置
- 单次字节上限按服务方独立可填，出厂值取各家官方限制；原文超出时从前面开始取、舍弃后面的
- 凭据 Keystore 加密落盘；凭据页防截屏；复制过的 Key 会从剪贴板历史里清掉

**配置备份**（设置页）
- 全部设置项 + 用户词频（可选剪贴板历史、已下载词库）导出为单个 `.jinn` 加密包
- AES-256-GCM + 中文汉字密码（本机不存）；导入可选「覆盖还原」或「仅并入数据」；导入前可预览备份时间 / 来源设备 / 各节条数

**语音听写**（可选，需自建服务端）
- 长按麦克风：按住说话松手识别，上滑取消；短按：连续录音，再点结束（3 分钟兜底）
- 本地 VAD 静音检测，减少无效上传

## 架构

```
【本机 · 无需服务端】拼音键盘 / 剪贴板面板 / 词库引擎 ← 输入法服务 JinnIme
【可选 · 需自建服务端】麦克风 → MicRecorder(16kHz PCM16) → AsrClient(WebSocket)
                              → NAS 上的 CapsWriter Offline → 识别文本（整段累积、整体覆盖）
```

| 模块 | 职责 |
|---|---|
| `JinnIme` | 输入法服务：双模式、手势、结果回显、剪贴板粘贴 |
| `PinyinEngine` / `FuzzyPinyin` | 引擎：词库加载、候选查询、双拼、补全、分词 / 模糊音等价类（纯函数） |
| `PinyinKeyboardView` | 26 键键盘 + 候选栏 + 功能面板 |
| `ClipboardPanelView` / `SearchPanelView` | 剪贴板面板 / 顶部搜索面板 |
| `ClipboardDb` | 剪贴板库（SQLite + AES-256-GCM） |
| `KeyboardLayouts` / `KeyboardSkin` | 键盘布局数据 / 皮肤（零位图） |
| `KeyAppearanceActivity` / `DictManagerActivity` / `SymbolOrderActivity` / `FavoriteSymbolsActivity` | 外观页 / 词库页 / 符号排序页 / 收藏编辑页 |
| `ConfigBackup` / `ConfigBackupManager` / `ConfigCrypto` | 备份格式 / 打包解锁导入 / 加解密（JVM 可测） |
| `AsrClient` / `Diagnostics` | WebSocket 客户端（仅语音） / 日志与崩溃捕获 |

## 语音服务端（可选）

| 项 | 值 |
|---|---|
| 地址 / 端口 | 家用 NAS 局域网地址，`6016` |
| 协议 | `ws://<host>:6016`，子协议 `binary` |
| 音频 | 16kHz 单声道 float32 小端裸样本（Base64） |
| 分段 | `seg_duration=60`、`seg_overlap=4` |

通信对齐服务端 `core/protocol.py`：一次听写 = 若干 `is_final=false` 音频包 + 一个 `data=""` 的 `is_final=true` 收尾包；
服务端返回整段累积文本，客户端整体覆盖显示，不自行拼接。

## 构建

环境：JDK 17、Android SDK（compileSdk 34 / minSdk 26）、Gradle 8.9（项目内置 wrapper）。

```bash
python build_apk.py              # 编译 release → ./com.jinn.inputmethod.apk
python build_apk.py --install    # 编译并安装到设备
python build_apk.py --clean      # clean 后全新编译
./gradlew assembleDebug          # Debug（未签名）
./gradlew assembleRelease        # 无 JINN_KEYSTORE_* 时产出未签名包
```

**签名**：仓库不含密钥，禁止把密钥库 / 口令文件放进仓库目录。在仓库外生成后由环境变量注入
（cmd 用 `set JINN_KEYSTORE_ROOT=<凭据目录>`，PowerShell 用 `$env:JINN_KEYSTORE_ROOT="<凭据目录>"`），
再运行 `python build_apk.py`。不设该变量时，脚本按「共享工作区 → `%USERPROFILE%\JinnKeyStores`」
自动找含本包密钥的目录。也可以绕过脚本直接给 Gradle 传
`JINN_KEYSTORE_FILE` / `JINN_KEYSTORE_PASSWORD` / `JINN_KEY_ALIAS` / `JINN_KEY_PASSWORD`。

签名顺序不可调换：构建 → 去 META-INF → `zipalign -p 4` → `apksigner` → 校验
（apksigner 不对齐，写反会让 APK 未对齐、设备读资源需先解压）。

## 词库

内置（`app/src/main/assets/`，由 `tools/dict_builder/build_dicts.py` 生成）：
- `pinyin_index.bin.xz`：短语索引，约 29.9 万键 / 40 万条（按词频从高到低切分），二分查找按需解码
- `pinyin_chars.txt.xz`：单字表（9,373 字 / 10,080 条读音，含三档全部）
- `common_chars` / `tier2_chars` / `tier3_chars`.txt.xz：三档字表（5,613 / 837 / 2,923 字），档 2、档 3 按开关放行
- `simp_trad.txt.xz` + `simp_trad_words.txt.xz`：简繁对照（2,714 对逐字 + 9,139 条词级表），供「只使用繁体字」替换候选（词级优先，如 头发 → 頭髮 而非 頭發；逐字表源 OpenCC STCharacters 繁体优先、人工表只作覆盖；词级表含词级消歧与「往返一致」补收两类条目，同一繁体形下以词频最高的词典词为反查胜者）
- `simplify.txt.xz`：繁→简单字映射（2,965 项，源 OpenCC TSCharacters）——把繁体候选折回简体，词频 / 消费区间 / 排序的键都按简体走（缺了它，「頭髮」只折得回「头髮」）
- `pinyin_syllables.txt.xz`：合法音节全集（421 个；`lue`/`nue` 保留 —— 双拼输出 ue 型，切分与 üe 折返都要用）

可选词库不进 APK，在「分类词库」页按需下载到 `filesDir/dicts/`（第 2 / 3 / 4 部分，各 40 / 50 / 60 万条）。
构建工具见 `tools/dict_builder/`。

## 许可证与致谢

GPL-3.0（见 [LICENSE](LICENSE)）。内置词库与简繁映射的来源：雾凇拼音、白霜拼音（GPL-3.0），
万象拼音（CC BY 4.0），结巴分词（MIT）—— 因含 GPL-3.0 来源，整体以 GPL-3.0 分发。

[rime-ice 雾凇拼音](https://github.com/iDvel/rime-ice) ·
[rime-wanxiang 万象拼音](https://github.com/amzxyz/rime-wanxiang) ·
[jieba 结巴分词](https://github.com/fxsjy/jieba) ·
[CapsWriter Offline](https://github.com/HaujetZhao/CapsWriter-Offline) ·
[pypinyin](https://github.com/mozillazg/pypinyin)
