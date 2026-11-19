#!/usr/bin/env python3
"""Refresh the GitHub repository introduction after a verified Release publication."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.request
import textwrap

ROOT = Path(__file__).resolve().parents[1]
DESCRIPTION_LIMIT = 350
TOPICS = ["android", "ai-agent", "voice-assistant", "jetpack-compose", "gemini-style", "mcp", "android-assistant"]


def request(method: str, url: str, token: str, body: dict | None = None) -> dict:
    payload = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=payload, method=method, headers={
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
        "User-Agent": "Eta-Ultra-Repo-Metadata",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            content = response.read()
            return json.loads(content) if content else {}
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"GitHub {method} failed: HTTP {error.code}: {detail}") from None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-info", type=Path, default=Path("dist/release-info.json"))
    args = parser.parse_args()
    token = os.environ.get("REPO_ADMIN_TOKEN") or os.environ.get("GH_TOKEN")
    repository = os.environ.get("GITHUB_REPOSITORY", "q3330576893/Eta-por-ultra")
    if not token:
        raise SystemExit("REPO_ADMIN_TOKEN is required for automatic repository introduction updates")
    info = json.loads(args.release_info.read_text(encoding="utf-8"))
    readme = (ROOT / "README.md").read_text(encoding="utf-8").splitlines()
    summary = next((line.strip() for line in readme
                    if line.strip() and not line.startswith(("#", "<!--", "["))
                    and not any(word in line for word in ("最新正式版", "当前基础版本", "SHA-256", "来源提交"))),
                   "Android 系统级 AI 智能体与语音助手")
    summary = re.sub(r"\*\*|`", "", summary)
    summary = textwrap.shorten(summary, width=110, placeholder="…")
    description = (
        f"Eta Ultra {info['version']} | {summary} | "
        f"系统/Vosk 免费语音、本地快捷与天气备忘组件、会话模型对比采样、可点击出处知识库、NFC/规则试跑、星驰兼容配置、MCP/Skills；签名正式 APK。"
        f"最新版：{info['releaseUrl']}"
    )[:DESCRIPTION_LIMIT]
    repo_api = "https://api.github.com/repos/" + repository
    result = request("PATCH", repo_api, token, {
        "description": description,
        "homepage": "https://github.com/" + repository + "/releases/latest",
        "has_issues": True,
    })
    request("PUT", repo_api + "/topics", token, {"names": TOPICS})
    print(json.dumps({
        "repository": result["full_name"], "version": info["version"], "description": description,
        "homepage": result["homepage"], "tag": info["tag"],
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
