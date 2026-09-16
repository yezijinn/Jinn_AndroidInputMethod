# 词库构建工具（tools/dict_builder）

## 一、现行流水线（唯一在用）

```
docs/rime-ice/cn_dicts/{base,ext}.dict.yaml     雾凇拼音源（本机 docs/ 内，不入库）
        │
        │  convert_rime_ice.py
        ▼
tools/dict_builder/out/rime_ice/pinyin_phrases.txt   本项目格式（拼音<TAB>词1|词2|…）
        │
        │  切包：base = 词长 ≤4 字（含四字成语）／ ext = >4 字
        │  压 xz：preset=7, lc=4, pb=0
        ▼
app/src/main/assets/pinyin_phrases.txt.xz    基础包，随 APK（约 4.3MB）
release/dict_ext.txt.xz                      扩展包，Release 附件按需下载
```

可选包（同样走 Release 附件）：

```
docs/rime-ice/cn_dicts/tencent.dict.yaml
        │  convert_rime_tencent.py（该源没有拼音列，需自动注音）
        ▼
release/opt_tencent.txt.xz                   腾讯大词库可选包
```

**改完词库必须做的三件事**（少一件都算没改完）：

1. **重新生成 xz**：`app/build.gradle.kts` 里的 `noCompress += "xz"` 不可删（否则 APK 二次压缩、设备要先解压）。
2. **真机验证**：加载与输入（基础包加载约 6.4s，在后台线程，不阻塞 UI）。
3. **扩展包 / 可选包改动要重传两端 Release 附件**（GitHub + Gitee），并实测下载（比对 sha256）。
   只改基础包则随 APK 一起发布。

## 二、脚本一览

| 脚本 | 用途 | 状态 |
|---|---|---|
| `convert_rime_ice.py` | rime-ice → 本项目格式，输出 base/ext 与对比报告 | **在用（主流程）** |
| `convert_rime_tencent.py` | 腾讯大词库 → 可选包（自动注音） | **在用** |
| `detect_ambiguous_keys.py` | 检测「连写歧义」漏词键（防回归，配合 `add_words.py`） | 在用（工具类） |
| `add_words.py` | 向现有词库追加词条（如补连写歧义词、游戏词） | 在用（工具类） |
| `merge_chars.py` | 合并单字表 / 音节表（换词库时用：新为主体 + 旧补覆盖） | 备用 |
| `split_dict.py` | 把完整词库切 base / ext（输入由命令行给出） | 备用 |
| `build_thuocl_pinyin.py` | THUOCL → 自动注音（评估过，**未采纳**） | 评估 |
| `dict_builder.py` | 早期通用构建器（dict.yaml / TXT / CSV） | 历史 |
| `compare_dicts.py` | 新旧词库键对比（脚本里写死了旧机器的绝对路径，需改路径才可用） | 历史 |
| `extend_dict.py` / `merge_game_dicts.py` | THUOCL / 游戏词一次性扩充 | 历史 |

## 三、⚠ 已删除的旧源文件（2026-09-17）

`tools/dict_builder/source/pinyin_phrases.txt`
（29.4MB / 1,048,342 行 / sha256 `b3519a87176b22e45101fa418a9c859576834423e3efc7f77bd6c15a64500129`）
已按作者要求**删除**，原因：

- 第 28 轮起词库整体换成 rime-ice，**现行流水线读 `docs/rime-ice/`，不再读这个文件**；
- 文件里含 **573 行 GBK 乱码词条**——UTF-8 字节被按 GBK 解码的产物，例如
  `鍙樺姩涓嶅眳` 应为「变动不居」、`b煤d脿o` 应为 `bùdào`（连拼音键都坏了）；
- 它被 `.gitignore` 忽略（规则 `tools/dict_builder/source/`），**从未进版本库**，删除后无法从 git 恢复；
- 留着它只会让人误以为「改词库＝改这个文件」，一旦拿它重跑生成器就会把乱码带回词库。

> 历史脉络与验证数据见 `更新日志.md`：第 28 轮（换 rime-ice）、第 35 轮（删除该文件）。
