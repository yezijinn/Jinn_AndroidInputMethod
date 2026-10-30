#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""双端发布 APK：**Gitee 优先、GitHub 同步**（约定第 11 条 / unified-update-check 6.9）。

为什么顺序写死：
- 国内网络下 GitHub 常常不可达 / 被重置，先推 GitHub 会让「发版」这一步在最需要它的时候失败；
- 末端状态必须两端一致，因此顺序只能有一个：**能直连的那端先建**，另一端后置补齐。

用法（GITEE_TOKEN 走环境变量，GitHub 侧走已登录的 `gh`）：
    python tools/release_dual.py --tag 20261030 --notes-file .relnotes.md
    python tools/release_dual.py --tag 20261030 --notes-file .relnotes.md --dry-run

行为：
1. 校验：tag 是纯日期 8 位且**等于 APK 的 versionCode**（BUG.md L-1307）、APK 存在且 <5MB、
   发布说明 ≤10 行且 ≤200 字、本地哈希自洽
2. **本地 tag 重指**：`git tag -f <tag> HEAD`，并钉「tag 与 HEAD 同指」（同日覆盖发版靠它）
3. **Gitee**：先推 tag（回拉核对 = HEAD），再调 `.github/scripts/sync_gitee.py`
   （与 Actions 同一份实现，不重写）
4. **GitHub**：先推 tag（回拉核对 = HEAD），再 `gh release` 建 / 改 + 覆盖上传 +
   删其它 Release 同名附件 + 回拉哈希校验
5. 任一端失败即非 0 退出，绝不继续做下一端（Gitee 失败就不要动 GitHub）

⚠ 只推 tag 不动分支：发版的前置提交由人在发版前推好（本脚本不 `git push` 分支，避免顺手推走别人未审的提交）。

⚠ 步骤顺序由本文件的 [STEPS] 决定，`BuildToolGatesTest.双端发布脚本必须先Gitee后GitHub` 钉着。
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REPO = "yezijinn/Jinn_AndroidInputMethod"
APK_NAME = "com.jinn.inputmethod.apk"
MAX_APK_BYTES = 5 * 1024 * 1024
MAX_NOTE_LINES = 10
MAX_NOTE_CHARS = 200
# **发布顺序的唯一真源**：改这里的顺序等于改发版纪律，别在别处再写一遍
STEPS = ("gitee", "github")

ROOT = Path(__file__).resolve().parent.parent


def log(message: str) -> None:
    print(message, flush=True)


def fail(message: str) -> "SystemExit":
    return SystemExit(message)


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 256), b""):
            digest.update(chunk)
    return digest.hexdigest()


def is_plausible_tag(tag: str) -> bool:
    """`[0-9]{8}` 只是形状；还要是**真实存在的日期**（`20269999` / `20260231` 这种必须挡掉）。

    与 `UpdateChecker.normalizeTag` 的日期闸**同一意图**（打错的日期会被「检查更新」当成未来版本，
    此后每次检查都提示有新版本、点进去打不开，且不会自愈），但这边更严：那边只查
    「年 2000..2099 / 月 1..12 / 日 1..31」（不校大小月，注释写明是刻意的），这里按真实日历查。
    """
    year = int(tag[0:4])
    if not 2000 <= year <= 2099:
        return False
    try:
        datetime.date(year, int(tag[4:6]), int(tag[6:8]))
    except ValueError:
        return False
    return True


def find_aapt2() -> Path | None:
    """本地 aapt2（用来读 APK 的 versionCode）。SDK 位置：环境变量 → `local.properties`。"""
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        local = ROOT / "local.properties"
        if local.is_file():
            for line in local.read_text(encoding="utf-8").splitlines():
                if line.strip().startswith("sdk.dir="):
                    sdk = line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
                    break
    if not sdk:
        return None
    # 只认**纯数字版本目录**并按数值排序：字符串倒序会让 `9.0.0` 排在 `37.0.0` 之前，
    # `37.0.0-rc1` 这类预览目录也会被选中（工作流里那条 `sort -V` + 数字目录过滤同口径）
    candidates = []
    for tool in (Path(sdk) / "build-tools").glob("*/aapt2*"):
        version = tool.parent.name
        if not re.fullmatch(r"[0-9]+(\.[0-9]+)*", version):
            continue
        candidates.append(((tuple(int(x) for x in version.split(".")), tool.name), tool))
    return max(candidates)[1] if candidates else None


def apk_version_code(apk: Path) -> int:
    """APK 里的 versionCode（= 编译日期）。读不出来就**不放行** —— 见 [check_inputs]。"""
    tool = find_aapt2()
    if tool is None:
        raise fail(
            "找不到 aapt2（设 ANDROID_HOME，或在 local.properties 写 sdk.dir）："
            "无法核对 tag 与 APK 的 versionCode（BUG.md L-1307）"
        )
    dump = subprocess.run(
        [str(tool), "dump", "badging", str(apk)],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    ).stdout
    found = re.search(r"versionCode='([0-9]+)'", dump)
    if not found:
        raise fail(f"没能从 APK 读出 versionCode: {apk}")
    return int(found.group(1))


def check_inputs(args) -> dict:
    """本地闸门：任何一条不过就不发（发出去再改只能覆盖，代价是双端的两次上传 + 一次删附件）。"""
    tag = args.tag.strip()
    if not re.fullmatch(r"[0-9]{8}", tag):
        raise fail(f"tag 必须是纯日期 8 位数字（例 20261030）: {tag}")
    if not is_plausible_tag(tag):
        raise fail(f"tag 不是合法日期（打错的日期会让「检查更新」永久误报）: {tag}")
    apk = Path(args.apk)
    if not apk.is_file():
        raise fail(f"找不到 APK: {apk}")
    size = apk.stat().st_size
    if size >= MAX_APK_BYTES:
        raise fail(f"APK 体积超限（≥5MB）: {size} 字节")
    # tag 只能取编译日期（= versionCode）：对不上的包一旦发出去，同日覆盖规则会让它一直留着，
    # 用户永远被提示有更新却装不上（BUG.md L-1307；与工作流 生成发布说明 里的判据同一口径）
    code = apk_version_code(apk)
    if str(code) != tag:
        raise fail(f"tag {tag} 与 APK 的 versionCode {code} 不一致：版本号就是编译日期，tag 只能取它（BUG.md L-1307）")
    notes_path = Path(args.notes_file)
    if not notes_path.is_file():
        raise fail(
            f"找不到发布说明文件: {notes_path}（Release 正文 ≤10 行且 ≤200 字；该文件被 gitignore，"
            "先在仓库根写一份：首行版本与日期，其余只写本版新增 / 修复 / 验证 + 一句「详细变更见 更新日志.md」）"
        )
    notes = notes_path.read_text(encoding="utf-8")
    body = notes if notes.endswith("\n") else notes + "\n"
    lines = len(body.rstrip("\n").splitlines())
    chars = len(body)
    if lines > MAX_NOTE_LINES or chars > MAX_NOTE_CHARS:
        raise fail(f"发布说明超限: {lines} 行 / {chars} 字（上限 10 行 / 200 字）")
    local = sha256_of(apk)
    if args.sha256 and args.sha256.strip().lower() != local:
        raise fail(f"传入的哈希与 APK 不符: {local}")
    return {
        "tag": tag,
        "apk": apk,
        "size": size,
        "sha256": local,
        "notes_file": Path(args.notes_file),
        "notes_lines": lines,
        "notes_chars": chars,
    }


def run(cmd, *, env=None) -> None:
    log("  $ " + " ".join(cmd))
    proc = subprocess.run(cmd, cwd=str(ROOT), env=env)
    if proc.returncode != 0:
        raise fail(f"命令失败（退出码 {proc.returncode}）: {cmd[0]}")


def git_output(*args: str) -> str:
    """只读的 git 查询（`rev-parse` / `ls-remote`）：失败返回空串，由调用方判定。"""
    proc = subprocess.run(["git", *args], cwd=str(ROOT), capture_output=True,
                          text=True, encoding="utf-8", errors="replace")
    return proc.stdout.strip() if proc.returncode == 0 else ""


def head_commit() -> str:
    head = git_output("rev-parse", "HEAD")
    if not head:
        raise fail("读不到 git HEAD（当前目录不是仓库，或 git 不在 PATH）")
    return head


def local_tag_commit(tag: str) -> str:
    return git_output("rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}")


def assert_tag_commit(where: str, got: str, want: str) -> None:
    """守卫的唯一判据：**该处的 tag 必须与 HEAD 同指**（本地与双端用同一条）。"""
    if got != want:
        raise fail(f"{where} 的 tag 与 HEAD 不同指: {got or '(空)'} vs {want}")


def repoint_tag(tag: str) -> str:
    """把本地 tag 重指到 HEAD，并钉死「HEAD 与 tag 同指」。

    同日覆盖发版会复用同一个 tag，只推代码不重指 tag ⇒ 双端 tag 停在旧提交
    （2026-10-30 就是手工补的）。这里先重指本地，再让两端各自回拉核对。
    """
    head = head_commit()
    if local_tag_commit(tag) != head:
        run(["git", "tag", "-f", tag, "HEAD"])
    assert_tag_commit("本地", local_tag_commit(tag), head)
    log(f"本地 tag {tag} -> {head[:9]}（与 HEAD 同指）")
    return head


def push_tag(remote: str, tag: str, head: str) -> None:
    """推 tag 到指定远端，再**回拉**核对该端 tag 与 HEAD 同指（不信任 push 的退出码）。"""
    run(["git", "push", "--force", remote, f"refs/tags/{tag}:refs/tags/{tag}"])
    lines = git_output("ls-remote", "--tags", remote, f"refs/tags/{tag}").splitlines()
    # 附注 tag 会有两行（标签对象 + `^{}` 指向的提交）；优先取剥皮后的那一行
    peeled = [l for l in lines if l.endswith(f"refs/tags/{tag}^{{}}")]
    plain = [l for l in lines if l.endswith(f"refs/tags/{tag}")]
    picked = (peeled or plain)
    remote_sha = picked[0].split()[0] if picked else ""
    assert_tag_commit(remote, remote_sha, head)
    log(f"{remote} tag {tag} 校验通过: {remote_sha[:9]} = HEAD")


def release_gitee(info: dict, target: str, repo: str) -> None:
    """Gitee 侧：复用 Actions 用的同一份脚本（建 / 改 Release、覆盖附件、回拉校验）。

    [repo] 与 GitHub 侧同源（同一仓库在两端同名）：别让 `--repo` 只对一半生效 ——
    那样一端推 A、另一端推 B，回拉校验各自过、双端却不一致。
    """
    script = ROOT / ".github" / "scripts" / "sync_gitee.py"
    if not script.is_file():
        raise fail(f"缺少 {script}（Gitee 发布实现只有这一份，别另写一套）")
    run(
        [
            sys.executable,
            str(script),
            "--repo", repo,
            "--tag", info["tag"],
            "--apk", str(info["apk"]),
            "--sha256", info["sha256"],
            "--notes-file", str(info["notes_file"]),
            "--target", target,
        ]
    )


def remote_sha256(url: str, retries: int = 3) -> str:
    error = None
    for attempt in range(1, retries + 1):
        try:
            digest = hashlib.sha256()
            with urllib.request.urlopen(url, timeout=300) as response:
                for chunk in iter(lambda: response.read(1024 * 256), b""):
                    digest.update(chunk)
            return digest.hexdigest()
        except (urllib.error.HTTPError, urllib.error.URLError) as exc:
            error = exc
            log(f"  GitHub 下载校验第 {attempt}/{retries} 次失败: {exc}")
            time.sleep(3 * attempt)
    raise fail(f"GitHub 下载校验失败: {error}")


def release_github(info: dict, target: str, repo: str) -> None:
    """GitHub 侧：后置同步。与工作流同一套动作（覆盖上传 + 只留最新一版 + 回拉哈希）。"""
    if shutil.which("gh") is None:
        raise fail("找不到 gh（GitHub CLI）；先 gh auth login，或改用仓库 Actions 发版")
    tag = info["tag"]
    notes = str(info["notes_file"])
    exists = subprocess.run(
        ["gh", "release", "view", tag, "--repo", repo],
        cwd=str(ROOT), capture_output=True,
    ).returncode == 0
    if exists:
        run(["gh", "release", "edit", tag, "--repo", repo, "--title", tag, "--notes-file", notes])
    else:
        run([
            "gh", "release", "create", tag, "--repo", repo,
            "--title", tag, "--notes-file", notes, "--target", target,
        ])
    run(["gh", "release", "upload", tag, str(info["apk"]), "--repo", repo, "--clobber"])
    # 只保留最新一版：其它 Release 上的同名附件删掉（Release 与 tag 保留）
    listed = subprocess.run(
        ["gh", "release", "list", "--repo", repo, "--limit", "200", "--json", "tagName",
         "-q", ".[].tagName"],
        cwd=str(ROOT), capture_output=True,
    )
    for other in listed.stdout.decode("utf-8", "replace").split():
        if other == tag:
            continue
        subprocess.run(
            ["gh", "release", "delete-asset", other, info["apk"].name, "--repo", repo, "--yes"],
            cwd=str(ROOT), capture_output=True,
        )
    url = f"https://github.com/{repo}/releases/download/{tag}/{info['apk'].name}"
    remote = remote_sha256(url)
    if remote != info["sha256"]:
        raise fail(f"GitHub 下载哈希不一致: {remote}（本地 {info['sha256']}）")
    log(f"GitHub 校验通过: {url}")


def main() -> None:
    parser = argparse.ArgumentParser(description="双端发布 APK（Gitee 优先、GitHub 同步）")
    parser.add_argument("--tag", required=True, help="发布 tag（纯日期 8 位数字 = versionCode）")
    parser.add_argument("--apk", default=APK_NAME, help=f"本地 APK 路径（默认 {APK_NAME}）")
    parser.add_argument("--notes-file", default=".relnotes.md", help="发布说明文件（≤10 行且 ≤200 字）")
    parser.add_argument("--repo", default=REPO, help="GitHub owner/name（Gitee 侧固定同名仓库）")
    parser.add_argument("--target", default="main", help="两端 Release 指向的提交（默认 main）")
    parser.add_argument("--sha256", default="", help="可选：与本地哈希核对，不符即退出")
    parser.add_argument("--dry-run", action="store_true", help="只打印将要执行的顺序，不联网")
    args = parser.parse_args()

    info = check_inputs(args)
    log(
        f"tag {info['tag']} / {info['size']} 字节 / {info['notes_lines']} 行 {info['notes_chars']} 字 / "
        f"sha256 {info['sha256']}"
    )
    log("顺序（唯一真源 STEPS）: " + " → ".join(STEPS))
    if args.dry_run:
        log(f"  [local ] git tag -f {info['tag']} HEAD（先钉本地 tag 与 HEAD 同指）")
        for step in STEPS:
            if step == "gitee":
                log(f"  [gitee ] git push --force gitee refs/tags/{info['tag']} + 回拉核对 tag = HEAD")
                log("  [gitee ] .github/scripts/sync_gitee.py --tag … --apk … --sha256 …")
            else:
                log(f"  [github] git push --force origin refs/tags/{info['tag']} + 回拉核对 tag = HEAD")
                log("  [github] gh release create/edit + upload --clobber + 回拉哈希")
        log("dry-run：未联网、未改远端、未改本地 tag")
        return
    head = repoint_tag(info["tag"])
    for step in STEPS:
        if step == "gitee":
            log("1/2 Gitee（先发）")
            push_tag("gitee", info["tag"], head)
            release_gitee(info, args.target, args.repo)
        elif step == "github":
            log("2/2 GitHub（后置同步）")
            push_tag("origin", info["tag"], head)
            release_github(info, args.target, args.repo)
    # 收尾再核一次本地：中途有人动过 tag 也不能算成功
    if local_tag_commit(info["tag"]) != head:
        raise fail(f"收尾核对失败：本地 tag {info['tag']} 已不指向 {head[:9]}")
    log(f"双端已一致: {info['tag']} / {info['sha256']} / tag 与 HEAD 同指 {head[:9]}")


if __name__ == "__main__":
    main()
