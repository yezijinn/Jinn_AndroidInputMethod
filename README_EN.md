# Jinn Pinyin IME

A lightweight Android IME: QWERTY full pinyin / Shuangpin (7 schemes), clipboard history, optional
dictionaries. Everything core runs on-device, no server needed; APK ~2.7MB with no bundled speech datas.

English ｜ [中文](README.md)

> **Voice dictation is optional and not out-of-the-box**: recognition runs on a server. You must
> self-host [CapsWriter Offline](https://github.com/HaujetZhao/CapsWriter-Offline) (Docker) on a home
> NAS; the app streams audio to it over WebSocket. Without that server the feature cannot be used.
> The keyboard, clipboard and dictionaries are unrelated to voice and need no server.

## Features

**Pinyin keyboard (QWERTY)**
- Full pinyin / Shuangpin / English; 7 Shuangpin schemes (Ziranma, Xiaohe, Sogou, Microsoft, Ziguang, Zhineng ABC, Jiajia) with final/initial hints on keys (toggle in Settings; when off, keys show letters only); pick the scheme in Settings (applies globally)
- Character set: 5,613 common characters by default (given name / place name characters such as 囧, 淼, 喆, 昇 type straight away); "More rare characters" in Settings opens a page with two tiers — level 2 (837 chars) and level 3 (2,923 chars); tier 3 requires tier 2, and toggles take effect instantly
- "Traditional Chinese only" (Settings): candidates are replaced with their traditional forms on commit (爱 → 愛, 碍 → 礙), applied instantly; learning is still stored in simplified form, shared by both modes
- Incomplete-pinyin completion (`ni m` / `nim` → 你们) and lexicon-constrained segmentation (`xuni` → `xu+ni`)
- Candidate bar: the pinyin line sits across the top (keys pressed, or optionally the expanded pinyin, e.g. `vsgo` → `zhongguo`) with candidates below; single or double row layout (in double row the pinyin bar sits between the two rows); smart prediction (off by default); one-tap CN/EN
- Corner radius / gap / transparency in Settings (0~24dp / 0~8dp / 0~100%), applied instantly on release
- 32 keyboard skins (original dark / original light + 30 colour schemes; 16 light, 16 dark) — colours and texture only, drawn entirely in code, independent of the three sliders
- Light/dark switching: follow system / light / dark / scheduled; each of the two slots keeps its own chosen skin, and the keyboard skin follows the switch
- Fuzzy pinyin (off by default): 11 optional accent equivalence groups (zh⇄z, n⇄l, en⇄eng, …); when a sound misses, its alternative readings are added as extra candidates (precise candidates stay untouched and in their original order)
- Backspace: tap deletes one char, hold keeps deleting, double-tap-then-hold also clears committed text; the ✕ on the candidate bar clears the current input

**Clipboard history** (embedded panel)
- Copies are auto-saved, AES-256-GCM encrypted into a private local DB readable only by this IME
- Categories All / URL / Number / Favorites; dynamic numbering, tap to paste, long-press to favorite or delete; clearing asks for confirmation and keeps favorites
- Top search panel: filters history as you type, tap to paste

**Dictionaries and learning**
- Optional packs in Settings ("Supplement phrase dictionaries"): 400K entries built in, plus three downloadable packs — level 2 +400K (recommended), level 3 +500K, level 4 +600K; loaded only when idle, IME restarts automatically after add/removal
- User word frequency: remembers chosen candidates and ranks them higher; stored locally only, switchable

**Config backup** (Settings)
- Exports all settings plus word frequency (optionally clipboard history and downloaded dictionaries) into one encrypted `.jinn` file
- AES-256-GCM with a Chinese-character password (never stored); import as "restore over" or "merge data only"; contents previewed before import (time / source device / per-section counts)

**Voice dictation** (optional, needs a server)
- Hold the mic to talk, release to recognize, swipe up to cancel; tap for continuous recording, tap again to stop (3-min cap)
- Local VAD silence detection reduces useless uploads

## Architecture

```
【On-device · no server】pinyin keyboard / clipboard panel / dictionary engine ← IME service JinnIme
【Optional · server】Mic → MicRecorder (16kHz PCM16) → AsrClient (WebSocket)
                            → CapsWriter Offline on your NAS → cumulative text (overwrite-displayed)
```

| Module | Responsibility |
|---|---|
| `JinnIme` | IME service: pinyin / voice modes, gestures, echo, clipboard paste |
| `PinyinEngine` / `FuzzyPinyin` | Engine: lexicon loading, candidates, Shuangpin, completion, segmentation / fuzzy-pinyin equivalence (pure functions) |
| `PinyinKeyboardView` | 26-key keyboard + candidate bar + function panels |
| `ClipboardPanelView` / `SearchPanelView` | Clipboard panel / top search panel |
| `ClipboardDb` | Clipboard history SQLite (AES-256-GCM) |
| `KeyboardLayouts` / `KeyboardSkin` | Static layout data / skins (no image assets) |
| `KeyAppearanceActivity` / `DictManagerActivity` / `SymbolOrderActivity` / `FavoriteSymbolsActivity` | Appearance / dictionaries / symbol order / favorite symbols |
| `ConfigBackup` / `ConfigBackupManager` / `ConfigCrypto` | Backup format / pack-unlock-import / crypto (JVM-testable) |
| `AsrClient` / `Diagnostics` | WebSocket client (voice only) / logs and crash capture |

## Voice server (optional, self-hosted)

| Item | Value |
|---|---|
| Host / port | Home NAS LAN address, `6016` |
| Protocol | `ws://<host>:6016`, subprotocol `binary` |
| Audio | 16kHz / mono / float32 little-endian raw samples (Base64) |
| Segmentation | `seg_duration=60`, `seg_overlap=4` |

Strictly follows the server's `core/protocol.py`: one dictation = several `is_final=false` frames plus a
trailing `is_final=true` frame with `data=""`; the server returns cumulative text, which the client
overwrite-displays and never concatenates itself.

## Build

Requirements: JDK 17, Android SDK (compileSdk 34 / minSdk 26), Gradle 8.9 (bundled wrapper).

```bash
python build_apk.py              # builds release to ./jinn-release.apk
python build_apk.py --install    # build & install to a connected device
python build_apk.py --clean      # clean build
./gradlew assembleDebug          # Debug (unsigned)
./gradlew assembleRelease        # unsigned unless JINN_KEYSTORE_* is provided
```

**Signing**: the repo ships no keys, and keys must not be placed inside it. Create the keystore outside
the repo, then inject it via env vars: `JINN_KEYSTORE_ROOT='<credentials>' python build_apk.py`, or pass
`JINN_KEYSTORE_FILE` / `JINN_KEYSTORE_PASSWORD` / `JINN_KEY_ALIAS` / `JINN_KEY_PASSWORD` to Gradle.

The pipeline order must not change: build → strip META-INF → `zipalign -p 4` → `apksigner` → verify
(apksigner does not align; reversing the order leaves the APK unaligned, forcing resource decompression).

## Dictionaries

Built-in (`app/src/main/assets/`, generated by `tools/dict_builder/build_dicts.py`):
- `pinyin_index.bin.xz`: phrase index, ~299K keys / 400K entries (split by descending word frequency); byte-wise binary search with on-demand decoding
- `pinyin_chars.txt.xz`: character table (9,373 chars / 10,080 readings, covering all three tiers)
- `common_chars` / `tier2_chars` / `tier3_chars`.txt.xz: the three tiers (5,613 / 837 / 2,923 chars); tiers 2 and 3 are gated by their switches
- `simp_trad.txt.xz` + `simp_trad_words.txt.xz`: character map (2,714 pairs) plus the word-level table (9,139 entries) used by "Traditional Chinese only" (word match wins, e.g. 头发 → 頭髮 not 頭發; the character map comes from OpenCC STCharacters with a hand-curated override layer; the word table holds both disambiguation and round-trip entries, and the reverse winner per traditional form is the dictionary word with the highest frequency)
- `simplify.txt.xz`: traditional→simplified character map (2,965 entries, OpenCC TSCharacters) — folds candidates back so the word-frequency / consumption / ranking keys stay simplified (without it 頭髮 only folds to 头髮)
- `pinyin_syllables.txt.xz`: full valid-syllable set (421; `lue`/`nue` kept — the shuangpin output is ue-typed)

Optional dictionaries are downloaded on demand into `filesDir/dicts/` (parts 2 / 3 / 4: 400K / 500K / 600K
entries). Builder tools: `tools/dict_builder/`.

## License and credits

GPL-3.0 (see [LICENSE](LICENSE)). The bundled lexicon contains GPL-3.0 sources (rime-ice / bai-shuang),
so the project is distributed under GPL-3.0.

[CapsWriter Offline](https://github.com/HaujetZhao/CapsWriter-Offline) ·
[iDvel/rime-ice](https://github.com/iDvel/rime-ice) ·
[mozillazg/pypinyin](https://github.com/mozillazg/pypinyin)
