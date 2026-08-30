#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CapsWriterIME 一键构建脚本：编译带正式签名的 release APK。

用法：
    python build_apk.py              # 编译 release，复制 APK 到根目录
    python build_apk.py --install    # 编译后安装到已连接设备
    python build_apk.py --clean      # clean 后全新编译

签名：E:\JinnKeyStores\<applicationId>\release.jks（按包名自动选择）
产物：app/build/outputs/apk/release/app-release.apk
复制到：./jinn-release.apk
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
KEYSTORE_ROOT = Path(os.environ.get("JINN_KEYSTORE_ROOT", r"E:\JinnKeyStores"))
GRADLE = None


def load_unified_signing_env():
    """Load the package-specific external keystore without printing secret values."""
    key_dir = KEYSTORE_ROOT / APPLICATION_ID
    keystore = key_dir / "release.jks"
    password_file = KEYSTORE_ROOT / "JinnPassword.md"
    if not keystore.is_file():
        raise RuntimeError(f"未找到统一签名密钥: {keystore}")
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
    candidates = [
        ROOT / "gradlew.bat",
        Path(os.environ.get("LOCALAPPDATA", "")) / "Temp" / "opencode" / "gradle-8.10.2" / "bin" / "gradle.bat",
        shutil.which("gradle.bat") or shutil.which("gradle"),
    ]
    for c in candidates:
        if c and Path(c).exists():
            return str(c)
    return None


def find_build_tool(name):
    """Find an Android SDK build tool without depending on PATH."""
    sdk_roots = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        r"C:\Android\sdk",
    ]
    candidates = []
    for sdk_root in sdk_roots:
        if not sdk_root:
            continue
        build_tools = Path(sdk_root) / "build-tools"
        if build_tools.is_dir():
            candidates.extend(path / name for path in build_tools.iterdir() if path.is_dir())
    candidates.sort(reverse=True)
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    path_tool = shutil.which(name) or shutil.which(Path(name).stem)
    if path_tool:
        return path_tool
    raise RuntimeError(f"未找到 Android SDK 工具: {name}")


def remove_meta_inf(source, destination):
    """Remove AGP metadata before apksigner creates the final JAR signature."""
    with zipfile.ZipFile(source, "r") as src, zipfile.ZipFile(destination, "w") as dst:
        for info in src.infolist():
            if info.filename.upper().startswith("META-INF/"):
                continue
            dst.writestr(info, src.read(info.filename))

def run(cmd, cwd=None):
    print(f"> {cmd}")
    r = subprocess.run(cmd, cwd=cwd, shell=True)
    if r.returncode != 0:
        print(f"[错误] 命令失败: {cmd}", file=sys.stderr)
        sys.exit(r.returncode)
    return r

def main():
    ap = argparse.ArgumentParser(description="构建正式签名 release APK")
    ap.add_argument("--install", action="store_true", help="编译后安装到设备")
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
        verify_tool = find_build_tool("apksigner.bat")
    except RuntimeError as exc:
        print(f"[错误] {exc}", file=sys.stderr)
        sys.exit(1)
    sign_input = output_dir / "app-release-sign-input.apk"
    if sign_input.exists():
        sign_input.unlink()
    remove_meta_inf(apk, sign_input)

    signed_apk = output_dir / "app-release-signed.apk"
    if signed_apk.exists():
        signed_apk.unlink()
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
            str(sign_input),
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
    if final_apk.exists():
        final_apk.unlink()
    signed_apk.replace(final_apk)
    apk = final_apk
    sign_input.unlink(missing_ok=True)
    verify = subprocess.run(
        f'"{verify_tool}" verify --verbose "{apk}"',
        shell=True,
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
    required_schemes = {
        "v2": "Verified using v2 scheme (APK Signature Scheme v2): true",
        "v3": "Verified using v3 scheme (APK Signature Scheme v3): true",
    }
    missing = [name for name, marker in required_schemes.items() if marker not in signature_output]
    if missing:
        print(f"[错误] APK 未同时启用签名方案: {', '.join(missing)}", file=sys.stderr)
        sys.exit(1)
    print("签名校验: V1=false V2=true V3=true")

    # 3. 复制到根目录
    dest = ROOT / "jinn-release.apk"
    shutil.copy2(apk, dest)
    print(f"已生成: {dest} ({dest.stat().st_size / 1024 / 1024:.1f} MB)")

    # 4. 可选安装
    if args.install:
        r = subprocess.run("adb devices", shell=True, capture_output=True, text=True)
        devices = [l.split("\t")[0] for l in r.stdout.splitlines()[1:] if "device" in l]
        if not devices:
            print("[警告] 未检测到设备，跳过安装")
            return
        dev = devices[0]
        print(f"安装到: {dev}")
        run(f'adb -s {dev} install -r "{dest}"')
        print("安装完成")

if __name__ == "__main__":
    main()
