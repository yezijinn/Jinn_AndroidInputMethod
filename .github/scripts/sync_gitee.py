#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把已构建的 APK 同步到 Gitee Release（与 GitHub 端逐字节一致）。

用法（token 走环境变量 GITEE_TOKEN）：
    python3 sync_gitee.py --repo yezijinn/Jinn_AndroidInputMethod --tag 20261011 \
        --apk com.jinn.inputmethod.apk --sha256 <hex> --notes-file notes.md

行为：
1. 查 / 建 / 改 tag 对应 Release（标题固定为纯日期 tag）
2. 删掉该 Release 上的旧同名附件（覆盖式发布），再上传新包
3. 删掉其它 Release 的同名附件（仓库只留最新一版，Release 与 tag 保留）
4. 回拉下载链接校验 sha256，与本地一致才算成功

接口事实（踩过的坑）：
- token 位置按方法不同：GET / DELETE 走查询串，POST / PATCH 走表单；PATCH 只传 body 报 400，
  必须同时带 tag_name 与 name
- 附件删除必须带 release id，少这一段报 404
- 正文 JSON 中文**原样**（ensure_ascii=False），转义成 \\uXXXX 会被拒
- 接口偶发 502，重试后再判定失败
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

API = "https://gitee.com/api/v5"
DOWNLOAD = "https://gitee.com/{repo}/releases/download/{tag}/{name}"
RETRIES = 3


def log(message):
    print(message, flush=True)


def api_request(method, url, *, params=None, form=None, retries=RETRIES):
    """调 Gitee API。404 返回 None（表示不存在），502/503/504 重试，其它非 2xx 直接报错。"""
    if params:
        sep = "&" if "?" in url else "?"
        url = url + sep + urllib.parse.urlencode(params)
    error = ""
    for attempt in range(1, retries + 1):
        data = None
        headers = {"Accept": "application/json"}
        if form is not None:
            data = urllib.parse.urlencode(form).encode("utf-8")
            headers["Content-Type"] = "application/x-www-form-urlencoded; charset=utf-8"
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                raw = response.read()
            return json.loads(raw.decode("utf-8")) if raw else None
        except urllib.error.HTTPError as exc:
            text = exc.read().decode("utf-8", "replace")
            if exc.code == 404:
                return None
            if exc.code in (502, 503, 504) and attempt < retries:
                error = f"HTTP {exc.code}: {text[:200]}"
                log(f"  Gitee 接口 {exc.code}，第 {attempt}/{retries} 次重试…")
                time.sleep(2 * attempt)
                continue
            raise SystemExit(f"Gitee 接口失败 [{method} {url}] HTTP {exc.code}: {text[:500]}")
    raise SystemExit(f"Gitee 接口重试仍失败: {error}")


def delete_assets(repo, release_id, token, names):
    """删除指定 Release 上名字在 names 里的全部附件。"""
    files = api_request(
        "GET",
        f"{API}/repos/{repo}/releases/{release_id}/attach_files",
        params={"access_token": token, "per_page": 100},
    ) or []
    for item in files:
        if item.get("name") in names:
            api_request(
                "DELETE",
                f"{API}/repos/{repo}/releases/{release_id}/attach_files/{item['id']}",
                params={"access_token": token},
            )
            log(f"  已删旧附件 {item['name']} (id={item['id']})")


def upload_attachment(repo, release_id, token, apk_path):
    """multipart 上传附件（标准库手搓，不依赖第三方包）。"""
    boundary = "----JinnImeBoundary" + uuid.uuid4().hex
    body = io.BytesIO()

    def text_part(name, value):
        body.write(f"--{boundary}\r\n".encode())
        body.write(f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode())
        body.write(str(value).encode("utf-8"))
        body.write(b"\r\n")

    text_part("access_token", token)
    body.write(f"--{boundary}\r\n".encode())
    body.write(
        f'Content-Disposition: form-data; name="file"; filename="{apk_path.name}"\r\n'.encode()
    )
    body.write(b"Content-Type: application/octet-stream\r\n\r\n")
    body.write(apk_path.read_bytes())
    body.write(b"\r\n")
    body.write(f"--{boundary}--\r\n".encode())

    request = urllib.request.Request(
        f"{API}/repos/{repo}/releases/{release_id}/attach_files",
        data=body.getvalue(),
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=600) as response:
            response.read()
    except urllib.error.HTTPError as exc:
        text = exc.read().decode("utf-8", "replace")
        raise SystemExit(f"Gitee 附件上传失败 HTTP {exc.code}: {text[:500]}")


def list_releases(repo, token):
    """列出仓库全部 Release（分页拉满）。"""
    out = []
    page = 1
    while True:
        batch = api_request(
            "GET",
            f"{API}/repos/{repo}/releases",
            params={"access_token": token, "per_page": 100, "page": page},
        ) or []
        out.extend(batch)
        if len(batch) < 100:
            return out
        page += 1


def remote_sha256(url, retries=RETRIES):
    """回拉下载链接算哈希；刚上传时 CDN 可能有短暂延迟，失败按退避重试。"""
    error = None
    for attempt in range(1, retries + 1):
        try:
            digest = hashlib.sha256()
            with urllib.request.urlopen(url, timeout=300) as response:
                while True:
                    chunk = response.read(1024 * 256)
                    if not chunk:
                        break
                    digest.update(chunk)
            return digest.hexdigest()
        except (urllib.error.HTTPError, urllib.error.URLError) as exc:
            error = exc
            log(f"  Gitee 下载校验第 {attempt}/{retries} 次失败: {exc}")
            time.sleep(3 * attempt)
    raise SystemExit(f"Gitee 下载校验失败: {error}")


def main():
    parser = argparse.ArgumentParser(description="同步 APK 到 Gitee Release")
    parser.add_argument("--repo", required=True, help="owner/name")
    parser.add_argument("--tag", required=True, help="发布 tag（纯日期 8 位数字）")
    parser.add_argument("--apk", required=True, help="本地 APK 路径")
    parser.add_argument("--sha256", required=True, help="本地 APK 的 sha256")
    parser.add_argument("--notes-file", required=True, help="发布说明文件")
    args = parser.parse_args()

    token = (os.environ.get("GITEE_TOKEN") or "").strip()
    if not token:
        raise SystemExit("缺少环境变量 GITEE_TOKEN")
    if not args.tag.isdigit() or len(args.tag) != 8:
        raise SystemExit(f"tag 必须是纯日期 8 位数字: {args.tag}")

    apk_path = Path(args.apk)
    if not apk_path.is_file():
        raise SystemExit(f"找不到 APK: {apk_path}")
    local = hashlib.sha256(apk_path.read_bytes()).hexdigest()
    if local != args.sha256.strip().lower():
        raise SystemExit(f"本地 APK 哈希与传入不符: {local}")

    notes = Path(args.notes_file).read_text(encoding="utf-8")

    # 1. 建 / 改 Release：标题固定为纯日期 tag
    release = api_request(
        "GET",
        f"{API}/repos/{args.repo}/releases/tags/{args.tag}",
        params={"access_token": token},
    )
    if release:
        release_id = release["id"]
        api_request(
            "PATCH",
            f"{API}/repos/{args.repo}/releases/{release_id}",
            form={
                "access_token": token,
                "tag_name": args.tag,
                "name": args.tag,
                "body": notes,
                "target_commitish": "main",
            },
        )
        log(f"已更新 Gitee Release {args.tag} (id={release_id})")
    else:
        release = api_request(
            "POST",
            f"{API}/repos/{args.repo}/releases",
            form={
                "access_token": token,
                "tag_name": args.tag,
                "name": args.tag,
                "body": notes,
                "target_commitish": "main",
            },
        )
        release_id = release["id"]
        log(f"已创建 Gitee Release {args.tag} (id={release_id})")

    # 2. 覆盖式发布：先删当前 Release 的旧同名附件
    delete_assets(args.repo, release_id, token, {apk_path.name})

    # 3. 上传新包
    upload_attachment(args.repo, release_id, token, apk_path)
    log(f"已上传 {apk_path.name}")

    # 4. 仓库只留最新一版：删掉其它 Release 的同名附件
    for other in list_releases(args.repo, token):
        if str(other.get("tag_name")) == args.tag:
            continue
        delete_assets(args.repo, other["id"], token, {apk_path.name})

    # 5. 回拉校验：与本地哈希一致
    url = DOWNLOAD.format(repo=args.repo, tag=args.tag, name=apk_path.name)
    remote = remote_sha256(url)
    if remote != local:
        raise SystemExit(f"Gitee 下载哈希不一致: {remote}")
    log(f"Gitee 校验通过: {url} ({local})")


if __name__ == "__main__":
    main()
