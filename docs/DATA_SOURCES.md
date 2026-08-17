# 词库数据源记录（DATA SOURCES）

> 本文件记录 CapsWriterIME 词库的所有数据来源、许可证与再分发要求。
> 原则：词库数据与 App 代码分离；系统词库/用户词库分离；所有词库可重复构建。
> 最后更新：2026-08-15

## 当前状态

CapsWriterIME 当前内置词库（`app/src/main/assets/`）：
- `pinyin_phrases.txt`：约 105 万键，29.4MB（拼音串 → 词语）
- `pinyin_chars.txt`：416 键（音节 → 单字）
- `pinyin_syllables.txt`：416 行（合法音节全集）

> ⚠️ 现有基础词库源自雾凇/白霜拼音（GPL-3.0），本项目整体按 GPL-3.0 分发（见根目录 LICENSE）。

## 已接入数据源

| 数据源 | GitHub | License | 词条数 | 注音 | 说明 |
|--------|--------|---------|-------:|------|------|
| THUOCL | [thunlp/THUOCL](https://github.com/thunlp/THUOCL) | MIT | 11884 | pypinyin 自动注音 | 11 类：IT/地名/历史名人/食物/动物/财经/汽车/成语/法律/医学/诗词 |
| pypinyin | [mozillazg/pypinyin](https://github.com/mozillazg/pypinyin) | MIT | - | 注音工具 | 词组模式多音字注音 |
| 卡拉彼丘词库 | 用户提供 `docs/klbq_ime_dict.txt` | 自定义 | 165 新增 | 自带拼音 | 游戏角色名/地图/道具/技能（329 条源，165 条合并） |
| 游戏通用词库 | 用户提供 `docs/games_ime_dict.txt` | 自定义 | 528 新增 | 自带拼音 | 热门游戏名/角色/术语（1361 条源，528 条合并） |

> 词库扩充接入时间：2026-08-17，工具 `tools/dict_builder/extend_dict.py` 与 `merge_klbq.py` 流程。

## 候选数据源

### 主词库（GPL-3.0，质量最高）

| 数据源 | GitHub | License | 使用文件 | 规模（实测） | 修改内容 | 再分发要求 |
|--------|--------|---------|----------|--------------|----------|------------|
| 雾凇拼音 | [iDvel/rime-ice](https://github.com/iDvel/rime-ice) | GPL-3.0 | `cn_dicts/base.dict.yaml`、`ext.dict.yaml`、`8105.dict.yaml` | base 32.3 万 / ext 18.3 万 / 8105 0.9 万 | 去空格拼音、反转键值、同音聚合、转自然码 | 并入分发后整体需 GPL-3.0 开源 |
| 白霜拼音 | [gaboolic/rime-frost](https://github.com/gaboolic/rime-frost) | GPL-3.0 | `cn_dicts/base.dict.yaml`、`ext.dict.yaml`、`8105.dict.yaml`、`GB18030-2022.dict.yaml` | base 23.2 万 / ext 11.5 万 / 8105 0.9 万 / GB 5.6 万 | 同上 | 同上 |

> 说明：两者十方 tencent 大词库无注音（`columns: [text, weight]`），需用 8105/41448 单字表自动注音。

### 宽松许可替代（若需闭源/商业分发）

| 数据源 | GitHub / 来源 | License | 说明 |
|--------|---------------|---------|------|
| CC-CEDICT | [官方](https://cc-cedict.org/wiki/)，数据文件在 MDBG | CC BY-SA 4.0 | ~12 万条，`传统 简体 [pin1 yin1] /释义/`，带声调拼音；偏传统字形+英文释义；需署名+相同方式共享 |
| THUOCL | [thunlp/THUOCL](https://github.com/thunlp/THUOCL) | MIT | 清华开放中文词库，11 类（成语/人名/地名等）；仅词+频率，无拼音需自注音 |
| pypinyin | [mozillazg/pypinyin](https://github.com/mozillazg/pypinyin) | MIT | 词组拼音库，可做注音工具 |
| OpenCC | [BYVoid/OpenCC](https://github.com/BYVoid/OpenCC) | Apache-2.0 | 繁简转换词对，非拼音源 |

### 词频/语言模型数据

| 数据源 | GitHub | License | 说明 |
|--------|--------|---------|------|
| Rime essay | [rime/rime-essay](https://github.com/rime/rime-essay) | LGPL-3.0 | 通用字频/词频表（`essay.txt`，44 万行），无拼音，适合做权重参考 |
| 结巴分词 dict | [fxsjy/jieba](https://github.com/fxsjy/jieba) | MIT | 词+词频+词性，无拼音 |

## 统一词库结构（目标格式）

转换后每条词目：

```text
word       = 人工智能
pinyin     = ren gong zhi neng     # 全拼，音节空格分隔（多音字按词保存）
ziranma    = rf gs vi ng           # 自然码双拼编码（本项目唯一方案）
frequency  = 123456                # 词频
source     = rime-ice              # 数据源标识
category   = common                # 分类：common/idiom/name/place/tech/internet…
```

**只保存自然码**，不保存小鹤/微软/搜狗等其他双拼编码。

## 多音字原则

必须按「词」保存完整拼音，不能只按单字拼音生成：

```text
银行    yin hang      # 行 = hang
行业    hang ye       # 行 = hang（同字不同音）
行走    xing zou      # 行 = xing
```

## 构建工具

- `tools/dict_builder/`：从 Rime YAML / TXT / CSV 解析 → 清洗 → 去重 → 拼音标准化 → 多音字检查 → 词频合并 → 自然码转换 → 索引构建。
- 所有词库可重复构建，生成产物为 `app/src/main/assets/` 下的文本 asset。

## 合规检查清单（每次新增数据源执行）

- [ ] 有明确的 LICENSE 文件或声明
- [ ] 记录数据来源与原始出处
- [ ] 确认修改/再分发要求
- [ ] 确认与项目整体许可证兼容
- [ ] 在本文档登记
