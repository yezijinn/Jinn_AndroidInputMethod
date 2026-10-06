"""检查 APK 里的按键音效是否**未压缩**（STORED）。

为什么必须查：`KeyFeedback` / `TapSoundActivity` 都用 `AssetManager.openFd` 打开
`assets/sounds/keyboard/*.ogg`，而 `openFd` 对**已压缩**的条目会抛 IOException ——
表现是「按键彻底没声且没有任何提示」。这个失败模式只在打包配置变化时出现，
编译、单测、lint 全绿，只能在产物上查。

用法：
    python check_apk_assets.py                  # 默认查 debug 包
    python check_apk_assets.py <apk 路径>        # 查指定包（发布包必查）
    python check_apk_assets.py --all            # 查 build/outputs 下所有变体的包
条目名同时与 `TapSound.SOUND_COUNT` 逐项对拍（取样用前缀 + 后缀：某些 aapt2 版本会写入
文件名为 `assets/sounds/keyboard/` 的目录条目，子串包含会把它算成一个音效）。
退出码：0 = 全部未压缩且条目一致；1 = 有压缩条目 / 条目不一致 / 找不到包。
"""
import glob
import io
import os
import re
import sys
import zipfile

ROOT = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod'
NEEDLE = 'assets/sounds/keyboard/'
SUFFIX = '.ogg'


def expected_names():
    """从 TapSound.kt 的 SOUND_COUNT 推出应有的条目名（与发布对账同一口径）。"""
    src = io.open(os.path.join(ROOT, 'app/src/main/java/com/jinn/inputmethod/TapSound.kt'),
                  encoding='utf-8').read()
    m = re.search(r'const val SOUND_COUNT\s*=\s*(\d+)', src)
    if not m:
        print('!! TapSound.kt 里找不到 SOUND_COUNT（改名了？）')
        return None
    return sorted('kbd_%02d.ogg' % (i + 1) for i in range(int(m.group(1))))


def check(apk):
    if not os.path.isfile(apk):
        print('NO APK:', apk)
        return False
    z = zipfile.ZipFile(apk)
    entries = [i for i in z.infolist()
               if i.filename.startswith(NEEDLE) and i.filename.endswith(SUFFIX)]
    if not entries:
        print('!! %s 里没有 %s*.ogg 条目' % (os.path.basename(apk), NEEDLE))
        return False
    names = sorted(i.filename.rsplit('/', 1)[-1] for i in entries)
    want = expected_names()
    if want is not None and names != want:
        print('   !! 条目与 SOUND_COUNT 不一致：缺 %s 多 %s'
              % ([n for n in want if n not in names], [n for n in names if n not in want]))
        return False
    bad = [i.filename for i in entries if i.compress_type != 0]
    total = sum(i.file_size for i in entries)
    print('%s  %.2f MB  音效 %d 个 / %.1f KB' % (
        os.path.basename(apk), os.path.getsize(apk) / 1048576, len(entries), total / 1024))
    if bad:
        print('   !! 被压缩（openFd 会失败）：', bad)
        return False
    print('   全部 STORED（未压缩）OK')
    return True


def main():
    args = [a for a in sys.argv[1:]]
    if '--all' in args:
        apks = sorted(glob.glob(os.path.join(ROOT, 'app/build/outputs/apk/*/*.apk')))
        apks += sorted(glob.glob(os.path.join(ROOT, '*.apk')))
    elif args:
        apks = args
    else:
        apks = sorted(glob.glob(os.path.join(ROOT, 'app/build/outputs/apk/debug/*.apk')),
                      key=os.path.getmtime)
    if not apks:
        print('没有找到 APK：先跑 assembleDebug / build_apk.py')
        return 1
    ok = all(check(a) for a in apks)
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
