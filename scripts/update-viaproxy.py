#!/usr/bin/env python3
"""Download the latest successful official ViaProxy CI build safely."""

import datetime
import hashlib
import json
import os
import shutil
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

REPOSITORY = "ViaVersion/ViaProxy"
API_HEADERS = {
    "User-Agent": "NekoProxy-AutoUpdater",
    "Accept": "application/vnd.github+json",
}


def get_json(url):
    request = urllib.request.Request(url, headers=API_HEADERS)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def download(url, destination):
    request = urllib.request.Request(url, headers=API_HEADERS)
    with urllib.request.urlopen(request, timeout=120) as response, open(destination, "wb") as output:
        shutil.copyfileobj(response, output)


def main():
    if len(sys.argv) < 2:
        print("Usage: update-viaproxy.py <target-jar>", file=sys.stderr)
        return 2

    target = Path(sys.argv[1]).resolve()
    state_path = Path(str(target) + ".update-state.json")
    now = datetime.datetime.now(datetime.timezone.utc)

    if target.is_file() and state_path.is_file():
        try:
            state = json.loads(state_path.read_text(encoding="utf-8"))
            checked = datetime.datetime.fromisoformat(state["last_checked_utc"].replace("Z", "+00:00"))
            if (now - checked).total_seconds() < 24 * 60 * 60:
                print("[ViaProxy] 最近 24 小时已检查过更新。")
                return 0
        except (ValueError, KeyError, OSError):
            pass

    print("[ViaProxy] 正在查询 ViaProxy 官方最新成功构建...")
    runs = get_json(f"https://api.github.com/repos/{REPOSITORY}/actions/runs?per_page=100")
    successful = [
        run for run in runs.get("workflow_runs", [])
        if run.get("path") == ".github/workflows/build.yml"
        and run.get("head_branch") == "main"
        and run.get("status") == "completed"
        and run.get("conclusion") == "success"
    ]
    if not successful:
        print("[ViaProxy] 没有找到官方成功构建，保留当前版本。")
        return 0

    run = max(successful, key=lambda item: item.get("created_at", ""))
    if state_path.is_file() and target.is_file():
        try:
            state = json.loads(state_path.read_text(encoding="utf-8"))
            if state.get("commit") == run.get("head_sha"):
                state["last_checked_utc"] = now.isoformat()
                state_path.write_text(json.dumps(state, indent=2), encoding="utf-8")
                print(f"[ViaProxy] 已是最新官方构建 ({run['head_sha'][:8]})。")
                return 0
        except (ValueError, OSError):
            pass

    artifacts = get_json(run["artifacts_url"]).get("artifacts", [])
    artifact = next(
        (item for item in artifacts if item.get("name") == "Artifacts" and not item.get("expired")),
        None,
    )
    if not artifact:
        print("[ViaProxy] 最新构建产物不存在或已过期，保留当前版本。")
        return 0

    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="nekoproxy-viaproxy-") as temp_dir:
        temp = Path(temp_dir)
        archive_path = temp / "artifacts.zip"
        download(artifact["archive_download_url"], archive_path)

        digest = artifact.get("digest", "")
        if digest.startswith("sha256:"):
            hasher = hashlib.sha256()
            with open(archive_path, "rb") as archive_file:
                for chunk in iter(lambda: archive_file.read(1024 * 1024), b""):
                    hasher.update(chunk)
            if hasher.hexdigest().lower() != digest.split(":", 1)[1].lower():
                raise RuntimeError("官方构建产物 SHA-256 校验失败")

        with zipfile.ZipFile(archive_path) as archive:
            candidates = [
                name for name in archive.namelist()
                if name.lower().endswith(".jar")
                and not name.lower().endswith("+java8.jar")
            ]
            if not candidates:
                raise RuntimeError("官方构建产物中没有找到适用于 Java 17+ 的 ViaProxy JAR")
            jar_name = candidates[0]
            with archive.open(jar_name) as source, open(temp / "ViaProxy.jar", "wb") as output:
                shutil.copyfileobj(source, output)

        staged = temp / "ViaProxy.jar"
        with zipfile.ZipFile(staged) as jar:
            if "net/raphimc/viaproxy/ViaProxy.class" not in jar.namelist():
                raise RuntimeError("下载的 JAR 未通过 ViaProxy 主类校验")

        if target.is_file():
            timestamp = now.strftime("%Y%m%d-%H%M%S")
            backup = Path(str(target) + f".backup.{timestamp}")
            shutil.copy2(target, backup)
            print(f"[ViaProxy] 已备份旧版本: {backup}")

        staged_target = Path(str(target) + ".download")
        shutil.copy2(staged, staged_target)
        os.replace(staged_target, target)

    state_path.write_text(
        json.dumps(
            {
                "commit": run["head_sha"],
                "version": Path(jar_name).name,
                "last_checked_utc": now.isoformat(),
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    print(f"[ViaProxy] 已更新到官方构建: {Path(jar_name).name}")
    print("[ViaProxy] 新版本将在本次启动中生效。")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"[ViaProxy] 自动更新失败，保留现有版本: {error}", file=sys.stderr)
        sys.exit(1)
