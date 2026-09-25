# 词库构建工具

## 一、现行流水线（唯一在用）

```
docs/rime-ice/cn_dicts/{base,ext}.dict.yaml     雾凇拼音源（本机 docs/ 内，不入库）
        │  convert_rime_ice.py
        ▼
out/rime_ice/pinyin_phrases.txt                 本项目格式（拼音<TAB>词1|词2|…）
        │  切包：base = 词长 ≤4（含四字成语）／ ext = >4 字；压 xz（preset=7, lc=4, pb=0）
        │  build_dict_index.py（按词频排序 → 二进制索引 v2：keysBlob + 长度数组）
        ▼
app/src/main/assets/pinyin_index.bin.xz         基础包索引，随 APK（4.48MB，原始 14.4MB）
app/src/main/assets/hot_phrases.txt.xz          高频子集（4 万词 / 220KB，冷启动秒级可输入）
release/dict_ext.txt.xz                         扩展包，Release 附件按需下载
```

基础包**文本**由生成器输出到 `out/rime_ice/pinyin_phrases.txt`，只作对比（不进 APK）；
留档副本存 `docs/dict_builder/`（本地，不入库）。运行时读二进制索引
（`PhraseIndex` 二分查找 + 按需解码）。

可选包（同样走 Release 附件）：`docs/rime-ice/cn_dicts/tencent.dict.yaml`
→ `convert_rime_tencent.py`（源无拼音列，需自动注音）→ `release/opt_tencent.txt.xz`。

**改完词库必做三件事**（少一件都算没改完）：

1. 重跑 `gen_hot_dict.py` 生成高频子集；`app/build.gradle.kts` 的 `noCompress += "xz"` 不可删（否则 APK 二次压缩）
2. 真机验证加载与输入（后台线程；高频子集先就绪即可打字，全量索引为内存映射复用）
3. 扩展包 / 可选包改动要重传双端 Release 附件并实测下载（比对 sha256）；只改基础包则随 APK 发布

## 二、脚本一览

**在用**：`convert_rime_ice.py`（主流程）· `convert_rime_tencent.py`（可选包）·
`build_dict_index.py`（索引，改词库必跑，`--fixture` 出测试 fixture）·
`gen_hot_dict.py`（高频子集，改词库必须重跑，否则前缀性质被破坏）·
`gen_shuangpin_tables.py`（7 套双拼键位 → `ShuangpinSchemes.kt`，改键位只改这里）·
`verify_shuangpin_migration.py`（换表对拍 676 码）· `detect_ambiguous_keys.py` + `add_words.py`（连写歧义漏词检测 / 追加词条）

**备用**：`merge_chars.py`（合并单字表 / 音节表）· `split_dict.py`（切 base / ext）·
`export_dicts.py`（索引/发布包 → 标准文本，导出到 `docs/dict_review/` 供人工审核，含单字表三档拆分）

**历史与评估**：`build_thuocl_pinyin.py`（THUOCL 自动注音，未采纳）· `dict_builder.py`（早期通用构建器）·
`compare_dicts.py`（写死旧机器路径）· `extend_dict.py` / `merge_game_dicts.py`（一次性扩充）

## 三、已删除的旧源文件（2026-09-17）

`tools/dict_builder/source/pinyin_phrases.txt`（29.4MB / 1,048,342 行）已删除：现行流水线改读
`docs/rime-ice/`；该文件含 573 行 GBK 乱码词条（如 `鍙樺姩涓嶅眳` 应为「变动不居」；
`b煤d脿o` 应为 `bùdào`），且被 `.gitignore` 忽略、从未入库、无法恢复。
**别再拿它重跑生成器**，会把乱码带回词库。
