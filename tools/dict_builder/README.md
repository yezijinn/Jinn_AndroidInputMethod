# 词库构建工具

## 一、现行流水线（2026-09-27 起，唯一在用）

```
docs/所有词库/短语/词库_第{1,2,3,4}部分.txt     `词<TAB>拼音(空格分隔)<TAB>词频`（本机 docs/ 内，不入库）
docs/所有词库/单字/单字注音_{一级,二级,三级}简体.txt  `拼音<TAB>字`
docs/所有词库/单字/简繁对照.txt                `简体<TAB>繁体<TAB>拼音`
        │  build_dicts.py（一体化：单字三档 + 索引 + 下载包 + 简繁映射）
        ▼
app/src/main/assets/pinyin_index.bin.xz        内置短语索引（第 1 部分：29.9 万键 / 40 万条 / xz 2.15MB）
app/src/main/assets/pinyin_chars.txt.xz        单字表（三档并集：9,373 字 / 10,080 条）
app/src/main/assets/{common,tier2,tier3}_chars.txt.xz   档 1 / 档 2 / 档 3 字表（5,613 / 837 / 2,923 字）
app/src/main/assets/simp_trad.txt.xz           简繁映射（2,714 对，逐字；一简对多繁刻意不收）
app/src/main/assets/simp_trad_words.txt.xz     简繁词级消歧（9,139 条，整词优先于逐字）
app/src/main/assets/simplify.txt.xz            繁→简单字映射（2,965 项，源 OpenCC TSCharacters；折简体用）
release/dict_part{2,3,4}.txt.xz                分类词库下载包（40 / 50 / 60 万条，Release 附件）
```

运行时读二进制索引（`PhraseIndex` 二分查找 + 按需解码）；**加载是单段**（不再有高频子集）。

**改完词库必做三件事**（少一件都算没改完）：

1. 重跑 `build_dicts.py`（按改动范围用 `--only chars|parts|simp`）；
   `app/build.gradle.kts` 的 `noCompress += "xz"` 不可删（否则 APK 二次压缩）
2. 真机验证加载与输入（索引加载含首次写 `.idx` 缓存约 1.2s）
3. 下载包改动 → 重传双端 Release 附件（tag `dict-parts-20260927-v1`）→ 更新
   `OptionalDicts.ALL` 的 checksum → 实测下载并比对 sha256

## 二、脚本一览

**在用**：`build_dicts.py`（主流程，生成上面全部资产）· `build_dict_index.py`（索引格式，`--fixture` 出测试 fixture）·
`asset_io.py`（资产文本读写，`.txt.xz` 统一入口）·
`gen_shuangpin_tables.py`（7 套双拼键位 → `ShuangpinSchemes.kt`，改键位只改这里）·
`verify_shuangpin_migration.py`（换表对拍 676 码）· `detect_ambiguous_keys.py` + `add_words.py`（连写歧义漏词检测 / 追加词条）

**备用**：`merge_chars.py`（合并单字表 / 音节表）· `split_dict.py`（切 base / ext）·
`export_dicts.py`（索引 / 发布包 → 标准文本，导出到 `docs/dict_review/` 供人工审核；
**2026-09-27 已按三档方案适配**：档 1/2/3 拆分视图、两级「被过滤词条」口径、简繁字级 + 词级映射导出）

**历史与评估**：`build_thuocl_pinyin.py`（THUOCL 自动注音，未采纳）· `dict_builder.py`（早期通用构建器）·
`compare_dicts.py`（写死旧机器路径）· `extend_dict.py` / `merge_game_dicts.py`（一次性扩充）

## 三、已删除的旧源文件（2026-09-17）

`tools/dict_builder/source/pinyin_phrases.txt`（29.4MB / 1,048,342 行）已删除：现行流水线改读
`docs/rime-ice/`；该文件含 573 行 GBK 乱码词条（如 `鍙樺姩涓嶅眳` 应为「变动不居」；
`b煤d脿o` 应为 `bùdào`），且被 `.gitignore` 忽略、从未入库、无法恢复。
**别再拿它重跑生成器**，会把乱码带回词库。
