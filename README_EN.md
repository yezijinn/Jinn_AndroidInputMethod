# Jinn Pinyin IME

A lightweight Android IME: QWERTY full pinyin / Shuangpin (7 schemes), clipboard history, optional
dictionaries. Everything core runs on-device, no server needed; APK ~5.0MB with no bundled speech datas.

English ｜ [中文](README.md)

> **Voice dictation is optional and not out-of-the-box**: recognition runs on a server. You must
> self-host [CapsWriter Offline](https://github.com/HaujetZhao/CapsWriter-Offline) (Docker) on a home
> NAS; the app streams audio to it over WebSocket. Without that server the feature cannot be used.
> The keyboard, clipboard and dictionaries are unrelated to voice and need no server.

## Features

**Pinyin keyboard (QWERTY)**
- Full pinyin / Shuangpin / English; 7 Shuangpin schemes (Ziranma, Xiaohe, Sogou, Microsoft, Ziguang, Zhineng ABC, Jiajia) with final/initial hints on keys (toggle in Settings; when off, keys show letters only); pick the scheme in Settings (applies globally)
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
- Optional packs in Settings: long-word pack (~224K entries) and Tencent lexicon (~955K entries); loaded only when idle, IME restarts automatically after add/removal
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

Built-in (`app/src/main/assets/`):
- `pinyin_index.bin.xz`: base pack as a binary index, ~600K entries (≤4-char phrases incl. 4-char idioms); byte-wise binary search with on-demand decoding
- `hot_phrases.txt.xz`: high-frequency subset (40K words, ~220KB) — loads first so typing works as soon as the keyboard appears; the full index follows in the background
- `pinyin_chars.txt` (422 syllables → char), `pinyin_syllables.txt` (full valid-syllable set)

Optional dictionaries are downloaded on demand into `filesDir/dicts/`. Builder tools: `tools/dict_builder/`.

## License and credits

GPL-3.0 (see [LICENSE](LICENSE)). The bundled lexicon contains GPL-3.0 sources (rime-ice / bai-shuang),
so the project is distributed under GPL-3.0.

[CapsWriter Offline](https://github.com/HaujetZhao/CapsWriter-Offline) ·
[iDvel/rime-ice](https://github.com/iDvel/rime-ice) ·
[mozillazg/pypinyin](https://github.com/mozillazg/pypinyin)
