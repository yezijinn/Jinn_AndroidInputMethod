# Jinn Android IME

<div align="center">

A lightweight Android pinyin input method (IME): **QWERTY full pinyin / Shuangpin (7 schemes)**,
**clipboard history**, and **optional dictionaries**.

Everything runs on-device — **no server required**.

**English** ｜ [中文](README.md)

</div>

> ### About the optional voice dictation
>
> This project also ships an **optional** voice dictation feature — but it is **not** ready to use
> out of the box:
>
> Speech recognition runs **entirely on a server**. No model is bundled in the Android app.
> You must deploy [CapsWriter Offline](https://github.com/HaujetZhao/CapsWriter-Offline)
> (a Docker image) on a **home NAS of your own**, and the app streams audio to it over WebSocket.
>
> In other words: **without a reachable, self-hosted NAS server, the voice feature cannot be used
> at all.** It is not aimed at general users — only at those who already run that server.
>
> **The pinyin keyboard, clipboard, and dictionaries all run fully on-device and need no server.**

## ✨ Features

- **Pinyin keyboard (QWERTY)**: full pinyin, Shuangpin (**7 schemes**: Ziranma, Xiaohe, Sogou, Microsoft, Ziguang, Zhineng ABC, Jiajia) and English; key faces show the final/initial hints.
  - **Incomplete-pinyin completion**: `ni m` or `nim` completes to `ni + men` and recalls 「你们」.
  - **Dictionary-constrained segmentation**: `xuni` correctly segments as `xu + ni` (虚拟)
    rather than `xun + i` (寻).
  - Candidate prediction, one-tap CN/EN switch, double-tap + long-press backspace to clear all.
- **Clipboard history** (embedded panel): auto-saves copies (AES-256-GCM encrypted), with
  categories (All / URL / Number / Favorites / Private), dynamic numbering, tap-to-paste,
  long-press to favorite / delete / mark private, and clear confirmation.
  - **Top search panel**: a panel above the candidate bar filters history in real time;
    results scroll and paste on tap; exiting restores the normal keyboard.
  - Entries are AES-256-GCM encrypted in a private local DB, readable only by this IME.
- **Optional dictionaries**: long-word pack and Tencent lexicon are downloadable on demand
  from the settings page; loaded **lazily** so first-input readiness is unaffected;
  adding or removing a pack auto-restarts the IME to apply.
- **Zero bundled models**: no speech models shipped; APK is about **4.5MB**, depending only on
  `core-ktx`, `activity-ktx`, `okhttp3`, and `xz`.
- **(Optional) Voice dictation**: press-and-hold or tap the mic to speak; recognized text streams
  back and is committed. **Requires a self-hosted NAS server — see the note above.**
  - Hold the mic to talk, release to recognize; swipe **up** while holding to cancel.
  - Tap once for continuous recording, tap again to stop (auto-stop after 3 min).
  - Local VAD silence detection trims idle audio and reduces useless uploads.

## 🏗️ Architecture

The pinyin keyboard, clipboard, and dictionaries all run on-device. Voice dictation is an
optional side path that requires an external server:

```
【On-device · no server needed】
   Pinyin keyboard / clipboard panel / dictionary engine
        ↑
     IME service (JinnIme)

【Optional · self-hosted server required】—————————————
   Mic → MicRecorder (16kHz PCM16) → AsrClient (WebSocket)
                                        ↓
                        CapsWriter Offline on your home NAS
                                        ↓
                    recognized text (accumulated, overwrite-displayed)
```

| Module | Responsibility |
|---|---|
| `JinnIme` | IME service: pinyin + voice modes, gestures, echo, clipboard paste broadcast |
| `PinyinEngine` | Pinyin engine: lexicon (lazy optional packs), candidates, shuangpin, completion, segmentation |
| `KeyboardLayouts` | Static keyboard layout data (QWERTY rows / digit layer / symbol groups incl. kana) |
| `PinyinKeyboardView` | 26-key keyboard + candidate bar + function panels |
| `ClipboardPanelView` | Embedded clipboard panel (categories / paste / favorite / private / delete / clear) |
| `SearchPanelView` | Top search panel: results + input + exit |
| `ClipboardDb` | Clipboard history SQLite (AES-256-GCM) |
| `DictManagerActivity` | Optional-dictionary page: list + download / delete |
| `AsrClient` ⚠️ | WebSocket client: streaming upload, exponential-backoff reconnect (**voice path only**) |
| `Diagnostics` | Diagnostic logs, crash capture, trace ID |

## 🔌 Voice Server (optional, self-hosted)

> If you only use the pinyin keyboard / clipboard, **you can skip this section entirely.**

The server is CapsWriter Offline (Docker) deployed on a **home NAS**, port `6016`.

| Item | Value |
| --- | --- |
| Host | Home NAS LAN address, e.g. `192.168.1.3` |
| Port | `6016` |
| Protocol | `ws://<host>:6016`, subprotocol `binary` |
| Audio | 16kHz / mono / float32 little-endian raw samples (Base64) |
| Segmentation | `seg_duration=60`, `seg_overlap=4` |

The protocol strictly follows the server's `core/protocol.py`: one dictation consists of several
`is_final=false` audio frames plus a trailing `is_final=true` frame with `data=""`.
The server returns **cumulative text**, which the client overwrite-displays and never concatenates itself.

## 🛠️ Build

### Requirements
- JDK 17, Android SDK (compileSdk 34 / minSdk 26)
- Gradle 8.9 (bundled wrapper)

### One-click build (recommended)
```bash
python build_apk.py              # builds release to ./jinn-release.apk
python build_apk.py --install    # build & install to a connected device
python build_apk.py --clean      # clean build
```

### Manual build
```bash
./gradlew assembleDebug     # Debug (unsigned)
./gradlew assembleRelease   # Release (auto-signed with a local keystore)
```

### Signing
The open-source repo does **not** ship signing keys or passwords. To sign locally:
1. Create a keystore: `keytool -genkeypair -keystore keystore/jinn-release.jks -alias jinn ...`
2. Create `keystore.properties` in the project root (already git-ignored):
   ```properties
   storeFile=keystore/jinn-release.jks
   storePassword=***
   keyAlias=jinn
   keyPassword=***
   ```
3. `./gradlew assembleRelease` signs automatically.

> ⚠️ When using `build_apk.py`, the signing pipeline order **must not be changed**:
> build → strip META-INF → `zipalign -p 4` → `apksigner sign` → verify.
> apksigner does not align the APK; reversing the order leaves it unaligned, forcing the
> device to decompress resources on read.

## 📚 Dictionaries

Built-in assets (`app/src/main/assets/`):
- `pinyin_index.bin.xz`: the base pack as a **binary index**, about **600K entries** (phrases up
  to 4 chars, incl. 4-char idioms); decompressed, then looked up by byte-wise binary search with
  on-demand decoding — no line parsing, no hash table
- `hot_phrases.txt.xz`: a high-frequency subset (40K words, ~220KB). It loads first so the
  keyboard is usable as soon as it appears; the full index follows in the background
- `pinyin_chars.txt`: **422 syllables** (syllable → char)
- `pinyin_syllables.txt`: full valid-syllable set
- **Optional dictionaries are not bundled**: downloaded on demand from the "Optional dictionaries"
  page into `filesDir/dicts/`, scanned automatically at engine startup

Dictionary builder tools live in `tools/dict_builder/` (Rime → project format; THUOCL auto phonetic annotation).

## 📄 License

This project is licensed under **GNU GPL v3.0** (see [LICENSE](LICENSE)).

> ⚠️ The bundled lexicon contains GPL-3.0 sources (rime-ice / bai-shuang); under GPL copyleft
> the project is distributed as GPL-3.0. Third-party sources (THUOCL/MIT, pypinyin/MIT) are compatible.

## 🙏 Credits

- [CapsWriter Offline](https://github.com/HaujetZhao/CapsWriter-Offline) — optional ASR server
- [iDvel/rime-ice](https://github.com/iDvel/rime-ice) — lexicon
- [thunlp/THUOCL](https://github.com/thunlp/THUOCL) — Tsinghua open Chinese lexicon
- [mozillazg/pypinyin](https://github.com/mozillazg/pypinyin) — pinyin tool
