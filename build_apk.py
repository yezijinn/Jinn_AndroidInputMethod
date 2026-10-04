#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
精灵输入法 一键构建脚本：编译带正式签名的 release APK。

用法：
    python build_apk.py                      # 编译 release，复制 APK 到根目录
    python build_apk.py --install            # 编译后安装到已连接设备
    python build_apk.py --install --device <序列号>   # 多设备时明确指定目标
    python build_apk.py --clean              # clean 后全新编译

签名：密钥库根目录按以下顺序找（找到即用），也可以显式覆盖：
        1) 环境变量 JINN_KEYSTORE_ROOT（**设了就只用它**，打错路径会明确报错而不是换一个库）
        2) <workspace>/GLOBAL/credentials/JinnKeyStores   （本仓库所在的共享工作区）
        3) %USERPROFILE%\JinnKeyStores
      根目录下按包名取密钥：<根目录>/<applicationId>/release.jks，密码读同级的 JinnPassword.md。
产物：app/build/outputs/apk/release/app-release.apk
复制到：**根目录只留一个** ./com.jinn.inputmethod.apk —— 固定名，发布与装机都用它，
        因此根目录那份永远是本次构建的产物（发布前仍应核对 sha256，见 AGENTS.md 发版节）。
        同时清掉旧命名的遗留产物（jinn-release.apk、com.jinn.inputmethod.<14位时间戳>.APK）。

签名流程（顺序不可调换）：
    AGP 产物 → 去掉 META-INF → zipalign 4 字节对齐 → apksigner 签名 → 校验
    apksigner 不负责对齐，所以 zipalign 必须在它之前；否则产物未对齐，
    设备上读取资源需要先解压，影响启动速度与内存占用。
"""
import argparse
import shutil
import subprocess
import sys
import os
import re
import zipfile
from pathlib import Path

# 控制台输出用 UTF-8（避免 Windows GBK 打印中文/符号崩溃）
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent
APPLICATION_ID = "com.jinn.inputmethod"


def keystore_root_candidates():
    """按优先级列出密钥库根目录候选项。

    环境变量**优先且独占**：设了就只用它 —— 打错路径时宁可明确报错，也不要静默回落到
    另一个库上（拿错密钥签出的包更难排查）。
    """
    env = (os.environ.get("JINN_KEYSTORE_ROOT") or "").strip()
    if env:
        return [Path(env)]
    return [
        # 本仓库所在的共享工作区：<workspace>/PROJECTS/<repo> → <workspace>/GLOBAL/credentials/JinnKeyStores
        ROOT.parent.parent / "GLOBAL" / "credentials" / "JinnKeyStores",
        # 传统默认位置
        Path.home() / "JinnKeyStores",
    ]


def resolve_keystore_root():
    """找到**含本包密钥**的那个根目录（只认结构，不读任何秘密值）。"""
    tried = keystore_root_candidates()
    for root in tried:
        if (root / APPLICATION_ID / "release.jks").is_file():
            return root
    raise RuntimeError(
        "未找到统一签名密钥（已试过下列位置）：\n"
        + "\n".join(f"  - {root / APPLICATION_ID / 'release.jks'}" for root in tried)
        + "\n  请设置环境变量指向密钥库根目录后重试，例如：\n"
        r"  set JINN_KEYSTORE_ROOT=<密钥库根目录>"
    )


def load_unified_signing_env():
    """Load the package-specific external keystore without printing secret values."""
    root = resolve_keystore_root()
    keystore = root / APPLICATION_ID / "release.jks"
    password_file = root / "JinnPassword.md"
    if not password_file.is_file():
        raise RuntimeError(f"未找到统一签名密码文件: {password_file}")

    text = password_file.read_text(encoding="utf-8")
    def find_option(option):
        for line in text.splitlines():
            if not re.search(rf"`-{option}`", line, re.IGNORECASE):
                continue
            cells = [cell.strip().strip("`") for cell in line.split("|")]
            values = [cell for cell in cells[1:] if cell]
            if values:
                return values[-1]
        raise RuntimeError(f"统一签名密码文件缺少 -{option} 配置")

    # The shared key registry uses the application id as the key alias.
    os.environ.update({
        "JINN_KEYSTORE_FILE": str(keystore),
        "JINN_KEYSTORE_PASSWORD": find_option("storepass"),
        "JINN_KEY_PASSWORD": find_option("keypass"),
        "JINN_KEY_ALIAS": APPLICATION_ID,
        # Let apksigner create the final modern V2/V3 signature blocks.
        "JINN_APKSIGNER_ONLY": "1",
    })
    return keystore

# 查找 gradle（项目 wrapper 或本机临时安装）
def find_gradle():
    """找 gradle：项目 wrapper 优先（版本与仓库锁定），其次 GRADLE_HOME / PATH。"""
    if os.name == "nt":
        wrapper_names = ("gradlew.bat", "gradlew")
        exe = "gradle.bat"
    else:
        wrapper_names = ("gradlew", "gradlew.bat")
        exe = "gradle"
    candidates = [ROOT / name for name in wrapper_names]
    candidates += [
        Path(os.environ.get("GRADLE_HOME", "")) / "bin" / exe,
        Path(os.environ.get("LOCALAPPDATA", "")) / "Temp" / "opencode" / "gradle-8.10.2" / "bin" / "gradle.bat",
        shutil.which(exe) or shutil.which("gradle"),
    ]
    for c in candidates:
        if c and Path(c).exists():
            return str(c)
    return None


def sdk_root_candidates():
    """按优先级列出可能的 Android SDK 根目录（跳过空值、按路径去重）。

    `ANDROID_HOME` / `ANDROID_SDK_ROOT` 没设时不能只靠 PATH：`apksigner` / `zipalign`
    通常不在 PATH 上，而 SDK 的默认安装位置是确定的（Windows 是
    `%LOCALAPPDATA%\\Android\\Sdk`，本机就装了一份）。
    """
    local = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
    home = Path.home()
    raw = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        os.path.join(local, "Android", "Sdk"),      # Windows 默认
        str(home / "Library" / "Android" / "sdk"),  # macOS 默认
        str(home / "Android" / "Sdk"),              # Linux 默认
        r"C:\Android\sdk",
    ]
    seen, out = set(), []
    for item in raw:
        if not item or not str(item).strip():
            continue
        item = str(item).strip()
        key = os.path.normcase(os.path.normpath(item))
        if key not in seen:
            seen.add(key)
            out.append(item)
    return out


def find_build_tool(name):
    """Find an Android SDK build tool without depending on PATH."""
    candidates = []
    for sdk_root in sdk_root_candidates():
        build_tools = Path(sdk_root) / "build-tools"
        if build_tools.is_dir():
            candidates.extend(path / name for path in build_tools.iterdir() if path.is_dir())
    # 版本号降序：优先用最新 build-tools（目录名如 34.0.0 / 37.0.0）。
    # ⚠ 不能直接对 Path 排序：那是**字符串**比较，`9.0.0` 会排在 `34.0.0` 之前
    # （本机残留单数字主版本时会选中旧 apksigner / zipalign，新参数不被支持 ⇒ 签名白跑一轮；
    # 见 BUG.md L-77）。
    def version_key(path):
        out = []
        for chunk in path.parent.name.split("."):
            out.append(int(chunk) if chunk.isdigit() else -1)
        return tuple(out)

    candidates.sort(key=version_key, reverse=True)
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    path_tool = shutil.which(name) or shutil.which(Path(name).stem)
    if path_tool:
        return path_tool
    raise RuntimeError(f"未找到 Android SDK 工具: {name}")


def find_apksigner():
    """apksigner 在各平台的可执行名不同（Windows 是 .bat）。"""
    return find_build_tool("apksigner.bat" if os.name == "nt" else "apksigner")


def find_zipalign():
    """zipalign 在各平台的可执行名不同（Windows 是 .exe）。"""
    return find_build_tool("zipalign.exe" if os.name == "nt" else "zipalign")


def remove_meta_inf(source, destination):
    """去掉 AGP 写入的 META-INF，交给 apksigner 生成最终签名块。

    用 `dst.open(info, "w")` + 流式拷贝，而不是 `src.read()` 整块读：
    词库压缩包就有数 MB，整块读入内存没有必要；同时保留原条目的 compress_type，
    避免重压缩改变体积。
    """
    with zipfile.ZipFile(source, "r") as src, \
            zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            if info.filename.upper().startswith("META-INF/"):
                continue
            with src.open(info.filename) as fin, dst.open(info, "w") as fout:
                shutil.copyfileobj(fin, fout, 1024 * 256)

def run(cmd, cwd=None):
    print(f"> {cmd}")
    r = subprocess.run(cmd, cwd=cwd, shell=True)
    if r.returncode != 0:
        print(f"[错误] 命令失败: {cmd}", file=sys.stderr)
        sys.exit(r.returncode)
    return r


def parse_adb_devices(output):
    """`adb devices -l` 输出 → 可用设备序列号列表。

    ⚠ 不能按子串 `"device" in line` 过滤：表头 `List of devices attached` 自带这个子串、
    `???????????? no permissions` 行也有（会把表头 / 不可用设备算成设备，
    进而让多设备判断误拒或让 `--device` 校验误判；见 BUG.md L-72）。
    只认「第二列恰为 `device`」的行，且显式跳过表头与空行。
    """
    devices = []
    for line in output.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices"):
            continue
        cols = line.split()
        if len(cols) >= 2 and cols[1] == "device":
            devices.append(cols[0])
    return devices


def main():
    ap = argparse.ArgumentParser(description="构建正式签名 release APK")
    ap.add_argument("--install", action="store_true", help="编译后安装到设备")
    ap.add_argument("--device", help="安装目标设备序列号（多设备时必须指定；也可用环境变量 JINN_ADB_DEVICE）")
    ap.add_argument("--clean", action="store_true", help="clean 后全新编译")
    args = ap.parse_args()

    global GRADLE
    GRADLE = find_gradle()
    if not GRADLE:
        print("[错误] 找不到 gradle", file=sys.stderr)
        sys.exit(1)
    try:
        keystore = load_unified_signing_env()
    except (OSError, RuntimeError) as exc:
        print(f"[错误] {exc}", file=sys.stderr)
        sys.exit(1)
    print(f"Gradle: {GRADLE}")
    print(f"签名密钥: {keystore}")

    # 1. 编译
    tasks = ["clean", "assembleRelease"] if args.clean else ["assembleRelease"]
    run(f'"{GRADLE}" {" ".join(tasks)}', cwd=str(ROOT))

    # 2. 定位 APK
    output_dir = ROOT / "app" / "build" / "outputs" / "apk" / "release"
    unsigned_apk = output_dir / "app-release-unsigned.apk"
    signed_gradle_apk = output_dir / "app-release.apk"
    apk = unsigned_apk if unsigned_apk.exists() else signed_gradle_apk
    if not apk.exists():
        print(f"[错误] 产物不存在: {unsigned_apk}", file=sys.stderr)
        sys.exit(1)

    # Verify all requested APK signature schemes before copying the release artifact.
    try:
        verify_tool = find_apksigner()
        zipalign_tool = find_zipalign()
    except RuntimeError as exc:
        print(f"[错误] {exc}", file=sys.stderr)
        sys.exit(1)

    sign_input = output_dir / "app-release-sign-input.apk"
    aligned_apk = output_dir / "app-release-aligned.apk"
    signed_apk = output_dir / "app-release-signed.apk"
    # apksigner 还会额外产出 v4 签名的 .idsig 文件，必须一并清理，
    # 否则会在产物目录里留下误导性的中间文件。
    temp_apks = (sign_input, aligned_apk, signed_apk,
                 Path(str(signed_apk) + ".idsig"))
    for tmp in temp_apks:
        tmp.unlink(missing_ok=True)

    try:
        # 去掉 AGP 写入的 META-INF，交给 apksigner 生成最终签名块
        remove_meta_inf(apk, sign_input)

        # zipalign 必须在 apksigner 之前：apksigner 本身不负责对齐，
        # 而 remove_meta_inf 用 zipfile 重打包会打乱原有偏移。
        # 未对齐的 APK 在设备上需解压读取资源，影响启动与内存占用。
        za = subprocess.run(
            [zipalign_tool, "-p", "-f", "4", str(sign_input), str(aligned_apk)],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if za.returncode != 0:
            print(za.stdout, file=sys.stderr)
            print(za.stderr, file=sys.stderr)
            print("[错误] zipalign 对齐失败", file=sys.stderr)
            sys.exit(1)

        sign = subprocess.run(
            [
                verify_tool,
                "sign",
                "--ks", os.environ["JINN_KEYSTORE_FILE"],
                "--ks-pass", "env:JINN_KEYSTORE_PASSWORD",
                "--ks-key-alias", os.environ["JINN_KEY_ALIAS"],
                "--key-pass", "env:JINN_KEY_PASSWORD",
                "--v1-signing-enabled", "false",
                "--v2-signing-enabled", "true",
                "--v3-signing-enabled", "true",
                "--out", str(signed_apk),
                str(aligned_apk),
            ],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if sign.returncode != 0:
            print(sign.stdout, file=sys.stderr)
            print(sign.stderr, file=sys.stderr)
            print("[错误] APK 签名失败", file=sys.stderr)
            sys.exit(1)

        final_apk = output_dir / "app-release.apk"
        final_apk.unlink(missing_ok=True)
        signed_apk.replace(final_apk)
        apk = final_apk
    finally:
        # 无论成功失败都清掉中间产物，避免残留文件干扰排查
        for tmp in temp_apks:
            tmp.unlink(missing_ok=True)

    verify = subprocess.run(
        [verify_tool, "verify", "--verbose", str(apk)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if verify.returncode != 0:
        print(verify.stdout, file=sys.stderr)
        print(verify.stderr, file=sys.stderr)
        print("[错误] APK 签名校验失败", file=sys.stderr)
        sys.exit(1)
    signature_output = verify.stdout + verify.stderr
    # 用正则而非精确字符串匹配：apksigner 各版本的措辞/换行略有差异，
    # 精确串匹配会在升级 build-tools 后误报「未启用签名方案」。
    required_schemes = {
        "v2": r"v2\s+scheme[^:]*:\s*true",
        "v3": r"v3\s+scheme[^:]*:\s*true",
    }
    missing = [
        name for name, pattern in required_schemes.items()
        if not re.search(pattern, signature_output, re.IGNORECASE)
    ]
    if missing:
        print(f"[错误] APK 未同时启用签名方案: {', '.join(missing)}", file=sys.stderr)
        print(signature_output, file=sys.stderr)
        sys.exit(1)
    print("签名校验: V1=false V2=true V3=true")

    # 复核产物确实 4 字节对齐（zipalign -c 校验失败返回非 0）
    align_check = subprocess.run(
        [zipalign_tool, "-c", "4", str(apk)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if align_check.returncode != 0:
        print(align_check.stdout, file=sys.stderr)
        print("[错误] 产物未通过 4 字节对齐校验", file=sys.stderr)
        sys.exit(1)
    print("对齐校验: 4 字节对齐通过")

    # 3. 复制到根目录：**只留一个**固定命名的产物 ./com.jinn.inputmethod.apk。
    #    用一个固定名而不是时间戳名，是为了不发错包：根目录那份永远就是本次构建的产物，
    #    发布 / 装机都直接用它，不需要再从一堆历史副本里挑（用户 2026-10-02 明确要求）。
    output_apk = ROOT / f"{APPLICATION_ID}.apk"
    shutil.copy2(apk, output_apk)
    print(f"已生成: {output_apk} ({output_apk.stat().st_size / 1024 / 1024:.1f} MB)")

    # 清掉**旧命名**的遗留产物，别让根目录继续堆历史副本。
    # ⚠ 只按**精确模式**删，绝不用 `*.APK` 这类通配 —— Windows / PowerShell 的通配
    # 大小写不敏感，历史上正是它把发布包一起删掉了（见 `.workbuddy/memory` 2026-09-23 的记录）。
    # 时间戳模式要求「包名 + 恰好 14 位数字 + .APK」，因此永远匹配不到上面的 com.jinn.inputmethod.apk。
    # 大小写不敏感是必须的：glob 在 Windows 上本来就不区分大小写，
    # 正则若区分，小写扩展名的遗留件（…20261002090827.apk）会被 glob 选中却判不中 ⇒ 静默漏清（L-469）。
    ts_pattern = re.compile(rf"{re.escape(APPLICATION_ID)}\.\d{{14}}\.APK\Z", re.IGNORECASE)
    stale = [ROOT / "jinn-release.apk"]
    stale += [path for path in ROOT.glob(f"{APPLICATION_ID}.*.APK") if ts_pattern.match(path.name)]
    for old in stale:
        if old.is_file() and old.name != output_apk.name:
            old.unlink()
            print(f"已清理旧命名的产物: {old.name}")

    # 4. 可选安装
    if args.install:
        # ⚠ 三条失败路径必须**非 0 退出**：`--install` 是用户明确要求的一步，静默 return 会让 CI / `&&`
        # 链路以为「编译 + 安装都成功」（BUG.md L-72）。
        r = subprocess.run("adb devices -l", shell=True, capture_output=True, text=True)
        # 「adb 用不了」与「没有设备」必须分开说：混成一句会把「装 platform-tools / 配 PATH」
        # 这种可行动的原因说成「没有设备」，用户就去反复插拔、重启 adb 了（2026-10-02 审查）。
        if r.returncode != 0 or "List of devices attached" not in r.stdout:
            print(
                "[错误] adb 不可用（不在 PATH 上，或 adb server 没起来）：\n"
                f"  {(r.stderr or r.stdout or '').strip()[:500]}",
                file=sys.stderr,
            )
            sys.exit(1)
        devices = parse_adb_devices(r.stdout)
        if not devices:
            print("[错误] 未检测到可用设备（adb devices -l 里没有状态为 device 的行）", file=sys.stderr)
            sys.exit(1)
        # 多设备（另一台可能是别人的机器）时**不猜**：原来取 devices[0]，插拔顺序一变就装错机。
        # 用 --device / JINN_ADB_DEVICE 明确指定，或在只有一台设备时自动使用。
        dev = args.device or os.environ.get("JINN_ADB_DEVICE")
        if not dev:
            if len(devices) > 1:
                print(f"[错误] 检测到 {len(devices)} 台设备（{'、'.join(devices)}），"
                      f"请用 --device <序列号> 或 JINN_ADB_DEVICE 指定目标，避免装错机器", file=sys.stderr)
                sys.exit(1)
            dev = devices[0]
        if dev not in devices:
            print(f"[错误] 指定的设备 {dev} 不在已连接列表（{'、'.join(devices)}）", file=sys.stderr)
            sys.exit(1)
        print(f"安装到: {dev}")
        run(f'adb -s {dev} install -r "{output_apk}"')
        print("安装完成")

if __name__ == "__main__":
    main()
