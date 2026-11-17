#!/usr/bin/env python3
"""Package verified Release metadata and keep the README download panels current."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
START = "<!-- release-info:start -->"
END = "<!-- release-info:end -->"


def update_panel(path: Path, content: str) -> None:
    original = path.read_text(encoding="utf-8")
    if original.count(START) != 1 or original.count(END) != 1:
        raise ValueError(f"Expected exactly one release-info panel: {path.name}")
    updated = re.sub(re.escape(START) + r".*?" + re.escape(END),
                     START + "\n" + content.rstrip() + "\n" + END, original,
                     count=1, flags=re.DOTALL)
    path.write_text(updated, encoding="utf-8", newline="\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metadata", type=Path, required=True, help="AGP release/output-metadata.json")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--commit", required=True, help="Full source commit SHA")
    parser.add_argument("--repository", default="q3330576893/Eta-por-ultra")
    parser.add_argument("--run-url", default="")
    parser.add_argument("--tag", help="Optional release tag; must equal v<versionName>")
    parser.add_argument("--update-readme", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.commit):
        raise ValueError("Source commit must be a full 40-character SHA")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
        raise ValueError("Invalid repository name")
    metadata = json.loads(args.metadata.read_text(encoding="utf-8-sig"))
    elements = metadata.get("elements", [])
    if len(elements) != 1:
        raise ValueError("Expected one universal ARM64 Release APK")
    element = elements[0]
    version = element["versionName"]
    if not re.fullmatch(r"\d+(?:\.\d+){2,3}", version):
        raise ValueError("Public Release version must be numeric, without Debug/Beta suffixes")
    if metadata.get("applicationId") != "io.github.mangi.eta":
        raise ValueError("Public Release must use the stable application ID")
    tag = "v" + version
    if args.tag and args.tag != tag:
        raise ValueError("Tag does not match APK version")
    apk = args.metadata.parent / element["outputFile"]
    if not apk.is_file():
        raise FileNotFoundError(apk)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    target = args.output_dir / "Eta-Ultra.apk"
    shutil.copyfile(apk, target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    (args.output_dir / "SHA256SUMS.txt").write_text(digest + "  Eta-Ultra.apk\n", encoding="ascii")
    base = "https://github.com/" + args.repository
    info = {
        "name": "Eta Ultra", "version": version, "versionCode": element["versionCode"],
        "applicationId": metadata["applicationId"], "buildType": "release", "debuggable": False,
        "minSdk": 33, "abi": "arm64-v8a", "tag": tag, "commit": args.commit,
        "releaseUrl": base + "/releases/tag/" + tag,
        "downloadUrl": base + "/releases/download/" + tag + "/Eta-Ultra.apk",
        "latestDownloadUrl": base + "/releases/latest/download/Eta-Ultra.apk",
        "sha256": digest, "size": target.stat().st_size, "workflowUrl": args.run_url,
    }
    (args.output_dir / "release-info.json").write_text(
        json.dumps(info, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    changes_path = ROOT / "RELEASE_NOTES.md"
    changes = changes_path.read_text(encoding="utf-8") if changes_path.exists() else ""
    # Recent commits are evidence of the shipped tree, not inferred feature descriptions.
    log = subprocess.check_output(["git", "log", "-6", "--format=- %h %s", args.commit],
                                  cwd=ROOT).decode("utf-8", errors="replace").strip()
    notes = f"""# Eta Ultra {version}

本仓库维护的正式签名 Release 安装包。

- Android 13+ · ARM64 · applicationId `io.github.mangi.eta`
- 版本：`{version}` · versionCode `{element['versionCode']}`
- 来源提交：[{args.commit[:7]}]({base}/commit/{args.commit})
- 构建类型：Release · R8 优化 · debuggable=false
- 安装包：[Eta-Ultra.apk]({info['downloadUrl']})
- SHA-256: `{digest}`
- 升级说明：Eta Ultra 正式包复用本仓库发布证书。Debug 与其他 fork 的签名不同，首次换用需备份数据后重新安装。

{changes}

## 最近提交

{log}

构建记录：{args.run_url or '本地正式构建'}

上游来源：[Mangi-11/Eta](https://github.com/Mangi-11/Eta)、[Oo0520/Eta-por](https://github.com/Oo0520/Eta-por)。
[许可证]({base}/blob/{args.commit}/LICENSE)
"""
    (args.output_dir / "release-notes.md").write_text(notes, encoding="utf-8")
    if args.update_readme:
        update_panel(ROOT / "README.md", f"""**最新正式版：[{version}]({info['releaseUrl']})** · Android 13+ · ARM64

[下载签名正式 APK]({info['latestDownloadUrl']}) · [历史版本]({base}/releases) · [本版更新说明]({info['releaseUrl']})

来源提交：[`{args.commit[:7]}`]({base}/commit/{args.commit}) · versionCode `{element['versionCode']}`

SHA-256：`{digest}`""")
        update_panel(ROOT / "README_EN.md", f"""**Latest stable release: [{version}]({info['releaseUrl']})** · Android 13+ · ARM64

[Download signed Release APK]({info['latestDownloadUrl']}) · [All releases]({base}/releases) · [Release notes]({info['releaseUrl']})

Source: [`{args.commit[:7]}`]({base}/commit/{args.commit}) · versionCode `{element['versionCode']}`

SHA-256: `{digest}`""")
    print(json.dumps({"version": version, "tag": tag, "sha256": digest, "output": str(args.output_dir)}, ensure_ascii=False))
    output_path = __import__("os").environ.get("GITHUB_OUTPUT")
    if output_path:
        with open(output_path, "a", encoding="utf-8") as output:
            output.write(f"tag={tag}\nversion={version}\n")


if __name__ == "__main__":
    main()
