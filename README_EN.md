# Jinn Pinyin IME

<div align="center">

A lightweight Android pinyin input method (IME): **QWERTY full pinyin / Shuangpin (7 schemes)**,
**clipboard history**, and **optional dictionaries**.

Everything runs on-device — **no server required**.

**English** ｜ [中文](README.md)

</div>

> ### About the optional voice dictation
>
> Voice dictation is **optional** and does not work out of the box: recognition runs
> **entirely on a server**, and no model is bundled in the Android app. You must deploy
> [CapsWriter Offline](https://github.com/HaujetZhao/CapsWriter-Offline) (a Docker image)
> on a **home NAS of your own**, and the app streams audio to it over WebSocket.
>
> **Without a reachable, self-hosted NAS server, the voice feature cannot be used at all.**
> It targets users who already run that server, not general users.
>
> **The pinyin keyboard, clipboard, and dictionaries all run fully on-device and need no server.**

## Features

- **Pinyin keyboard (QWERTY)**: full pinyin, Shuangpin (**7 schemes**: Ziranma, Xiaohe, Sogou, Microsoft, Ziguang, Zhineng ABC, Jiajia) and English; key faces show the final/initial hints. Pick the input scheme in Settings (「拼音输入方案」: full pinyin + 7 Shuangpin schemes; applies globally).
  - **Incomplete-pinyin completion**: `ni m` or `nim` completes to `ni + men` and recalls 「你们」.
  - **Smart prediction** (off by default, switchable in Settings); the candidate bar always shows
    the **keys you actually pressed**, so a Shuangpin conversion that swallows letters never looks frozen.
  - One-tap CN/EN switch; **key corner radius, gap and keyboard transparency** live in Settings
    →「键盘按钮调整」(radius 0~24dp, gap 0~8dp, transparency 0~100%) — applied instantly on release.
  - Backspace gestures: tap to delete one character, hold to keep deleting (once the pinyin string is
    empty it keeps deleting committed text); double-tap then hold clears committed text too.
  - The **✕** at the right end of the candidate bar clears the current pinyin string and candidates
    (only shown while candidates/predictions are present); the bar then returns to the 6-button panel.
  - **Dictionary-constrained segmentation**: `xuni` correctly segments as `xu + ni` (虚拟)
    rather than `xun + i` (寻).
- **Clipboard history** (embedded panel): copies are auto-saved, **AES-256-GCM encrypted into a
  private local DB that only this IME can read**; categories All / URL / Number / Favorites,
  dynamic numbering, tap-to-paste, long-press to favorite or delete. Clearing asks for confirmation
  and keeps favorites.
  - **Top search panel**: above the candidate bar, filters history as you type; results scroll and
    paste on tap; exiting restores the 26-key layout.
- **Optional dictionaries** (Settings →「补充短语词库」): the long-word pack (~185K entries) and
  Tencent lexicon (~955K entries) are downloadable on demand; loaded **only when idle**, so typing is
  never blocked; adding or removing a pack auto-restarts the IME to apply.
- **Zero bundled models**: no speech models shipped; APK is about **5.0MB**, depending only on
  `core-ktx`, `activity-ktx`, `okhttp3`, and `xz`.
- **(Optional) Voice dictation**: press-and-hold or tap the mic to speak; recognized text streams
  back and is committed. **Requires a self-hosted NAS server — see the note above.**
  - Hold the mic to talk, release to recognize; swipe **up** while holding to cancel.
  - Tap once for continuous recording, tap again to stop (auto-stop after 3 min).
  - Local VAD silence detection trims idle audio and reduces useless uploads.

## Architecture

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
| `JinnIme` | IME service: pinyin + voice modes, gestures, echo, clipboard paste (in-process callback) |
| `PinyinEngine` | Pinyin engine: lexicon (lazy optional packs), candidates, shuangpin, completion, segmentation |
| `KeyboardLayouts` | Static keyboard layout data (QWERTY rows / digit layer / symbol groups incl. kana) |
| `PinyinKeyboardView` | 26-key keyboard + candidate bar + function panels |
| `ClipboardPanelView` | Embedded clipboard panel (categories / paste / favorite / delete / clear) |
| `SearchPanelView` | Top search panel: results + input (exit via the candidate-bar button or Enter / hide the keyboard) |
| `ClipboardDb` | Clipboard history SQLite (AES-256-GCM) |
| `DictManagerActivity` | Optional-dictionary page: list + download / delete |
| `KeyAppearanceActivity` | Keyboard appearance page: corner-radius / gap sliders |
| `SymbolOrderActivity` | Symbol-group order page: ↑↓ reorder / reset |
| `FavoriteSymbolsActivity` | "Favorites" symbol editor: add / remove per page |
| `AsrClient` ⚠️ | WebSocket client: streaming upload, exponential-backoff reconnect (**voice path only**) |
| `Diagnostics` | Diagnostic logs, crash capture, trace ID |

## Voice Server (optional, self-hosted)

> Skip this section if you are not using voice dictation.

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

## Build

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
./gradlew assembleRelease   # Release (needs JINN_KEYSTORE_* env vars; unsigned without them)
```

### Signing
The open-source repo does **not** ship signing keys or passwords, and keys must **not** be placed
inside the repository directory (archiving or copying the project would leak them). To sign locally:

1. Create the keystore **outside** the repo, e.g. `<credentials>/com.jinn.inputmethod/release.jks`:
   `keytool -genkeypair -keystore <credentials>/com.jinn.inputmethod/release.jks -alias jinn ...`
2. Point the build at it via environment variables (the `build_apk.py` path):
   ```bash
   JINN_KEYSTORE_ROOT='<credentials>' python build_apk.py
   ```
   or pass `JINN_KEYSTORE_FILE` / `JINN_KEYSTORE_PASSWORD` / `JINN_KEY_ALIAS` / `JINN_KEY_PASSWORD` to Gradle.
3. Without any of the above, `./gradlew assembleRelease` produces an **unsigned** APK
   (keyless builds stay supported).

> ⚠️ When using `build_apk.py`, the signing pipeline order **must not be changed**:
> build → strip META-INF → `zipalign -p 4` → `apksigner sign` → verify.
> apksigner does not align the APK; reversing the order leaves it unaligned, forcing the
> device to decompress resources on read.

## Dictionaries

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

Dictionary builder tools live in `tools/dict_builder/` (Rime → project format). The THUOCL
auto-annotation route was evaluated and dropped: those entries ship without pinyin, so the
annotation error rate is too high.

## License

This project is licensed under **GNU GPL v3.0** (see [LICENSE](LICENSE)).

> ⚠️ The bundled lexicon contains GPL-3.0 sources (rime-ice / bai-shuang); under GPL copyleft
> the project is distributed as GPL-3.0.

## Credits

- [CapsWriter Offline](https://github.com/HaujetZhao/CapsWriter-Offline) — optional ASR server
- [iDvel/rime-ice](https://github.com/iDvel/rime-ice) — lexicon
- [mozillazg/pypinyin](https://github.com/mozillazg/pypinyin) — pinyin tool
