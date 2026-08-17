#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CapsWriterIME 一键构建脚本：编译带正式签名的 release APK。

用法：
    python build_apk.py              # 编译 release，复制 APK 到根目录
    python build_apk.py --install    # 编译后安装到已连接设备
    python build_apk.py --clean      # clean 后全新编译

签名：keystore/jinn-release.jks（build.gradle.kts 已配置 signingConfigs）
产物：app/build/outputs/apk/release/app-release.apk
复制到：./jinn-release.apk
"""
import argparse
import shutil
import subprocess
import sys
import os
from pathlib import Path

# 控制台输出用 UTF-8（避免 Windows GBK 打印中文/符号崩溃）
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent
GRADLE = None

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
    print(f"Gradle: {GRADLE}")

    # 1. 编译
    tasks = ["clean", "assembleRelease"] if args.clean else ["assembleRelease"]
    run(f'"{GRADLE}" {" ".join(tasks)}', cwd=str(ROOT))

    # 2. 定位 APK
    apk = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
    if not apk.exists():
        print(f"[错误] 产物不存在: {apk}", file=sys.stderr)
        sys.exit(1)

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
