# 键盘音效清单 (assets/sounds/keyboard/)

- 命名：`kbd_NN.ogg`（中性序号，按时长升序）。角色→音效映射放设置/代码，不写进文件名。
- OGG 进 APK（约 4–5 KB/个，aapt 默认不二次压缩）；WAV 母版留在本地 `tools/sound_preview/masters/`，
  **不入库** —— 原始下载素材不单独再分发（各站点的免费音效许可都禁止把素材本身当素材再上传）。
- 全部为**真实键盘录音**：Freesound 条目为 CC0；Mixkit 条目为 Mixkit 免费音效（免费商用、无需署名）。
- `onsets` 为起音数（1=纯单音，2=极短的 click+触底双瞬态，仍在同一次按键内）。

| 文件 | 原始名 | 来源 | 时长 | onsets | 建议角色 |
|---|---|---|---|---|---|
| `kbd_01.ogg` | `mx2541` | Mixkit · sfx/2541 · https://assets.mixkit.co/active_storage/sfx/2541/2541-preview.mp3 | 60ms | 1 | 待定 |
| `kbd_02.ogg` | `mixkit-hard-single-key-press-in-a-laptop-2542` | Mixkit · sfx/2542 · https://assets.mixkit.co/active_storage/sfx/2542/2542-preview.mp3 | 66ms | 1 | 待定 |
| `kbd_03.ogg` | `var_d` | 由 `mx2541` 派生的轻量变体（音高/EQ 微调，仍为真实音） | 67ms | 1 | 待定 |
| `kbd_04.ogg` | `015` | Freesound CC0 · id 631690 · https://cdn.freesound.org/previews/631/631690_151878-lq.ogg | 73ms | 1 | 待定 |
| `kbd_05.ogg` | `var_b` | 由 `015` 派生的轻量变体（音高/EQ 微调，仍为真实音） | 79ms | 1 | 待定 |
| `kbd_06.ogg` | `maybe_012` | Freesound CC0 · id 631731 · https://cdn.freesound.org/previews/631/631731_151878-lq.ogg | 109ms | 1 | 待定 |
| `kbd_07.ogg` | `var_a` | 由 `006` 派生的轻量变体（音高/EQ 微调，仍为真实音） | 114ms | 1 | 待定 |
| `kbd_08.ogg` | `006` | Freesound CC0 · id 701113 · https://cdn.freesound.org/previews/701/701113_15173053-lq.ogg | 122ms | 1 | 待定 |
| `kbd_09.ogg` | `maybe_014` | Freesound CC0 · id 752745 · https://cdn.freesound.org/previews/752/752745_14222278-lq.ogg | 135ms | 1 | 待定 |
| `kbd_10.ogg` | `maybe_010` | Freesound CC0 · id 253215 · https://cdn.freesound.org/previews/253/253215_789068-lq.ogg | 138ms | 2 | 六组默认（文字/数字/符号/删除/确认/功能） |
| `kbd_11.ogg` | `var_c` | 由 `028` 派生的轻量变体（音高/EQ 微调，仍为真实音） | 152ms | 1 | 待定 |
| `kbd_12.ogg` | `005` | Freesound CC0 · id 734201 · https://cdn.freesound.org/previews/734/734201_15961219-lq.ogg | 159ms | 1 | 待定 |
| `kbd_13.ogg` | `028` | Freesound CC0 · id 506765 · https://cdn.freesound.org/previews/506/506765_3797507-lq.ogg | 171ms | 1 | 待定 |
| `kbd_14.ogg` | `mixkit-single-key-type-2533` | Mixkit · sfx/2533 · https://assets.mixkit.co/active_storage/sfx/2533/2533-preview.mp3 | 183ms | 1 | 待定 |
| `kbd_15.ogg` | `maybe_057` | Freesound CC0 · id 842480 · https://cdn.freesound.org/previews/842/842480_10196790-lq.ogg | 205ms | 2 | 待定 |
| `kbd_16.ogg` | `maybe_003` | Freesound CC0 · id 491920 · https://cdn.freesound.org/previews/491/491920_10630312-lq.ogg | 210ms | 2 | 待定 |

