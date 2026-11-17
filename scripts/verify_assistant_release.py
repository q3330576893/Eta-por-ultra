#!/usr/bin/env python3
"""核验本版助理必需资产、已删除入口、原生库对齐与启动配置。"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

MODEL_SHA256 = "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba"
MODEL_BYTES = 43_898_754


def elf_load_alignments(data: bytes) -> list[int]:
    if data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("预期64位小端ELF")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phsize, phcount = struct.unpack_from("<HH", data, 54)
    if phsize < 56 or phcount > 1024:
        raise ValueError("ELF程序头无效")
    return [struct.unpack_from("<Q", data, phoff + index * phsize + 48)[0]
            for index in range(phcount)
            if struct.unpack_from("<I", data, phoff + index * phsize)[0] == 1]


def verify(apk: Path) -> dict:
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        model = archive.read("assets/offline-asr/vosk-model-small-cn-0.22.zip")
        if len(model) != MODEL_BYTES or hashlib.sha256(model).hexdigest() != MODEL_SHA256:
            raise ValueError("内置中文识别模型身份不匹配")
        manifest = json.loads(archive.read("assets/offline-asr/manifest.json"))
        if manifest["archiveSha256"] != MODEL_SHA256 or manifest["archiveBytes"] != MODEL_BYTES:
            raise ValueError("模型清单身份不匹配")
        for required in ("assets/offline-asr/LICENSE-JNA.txt", "assets/offline-asr/LICENSE-vosk-Apache-2.0.txt",
                         "assets/offline-asr/NOTICE.txt"):
            if not archive.read(required):
                raise ValueError("缺少第三方声明：" + required)
        native = {}
        for library in ("libvosk.so", "libjnidispatch.so"):
            path = "lib/arm64-v8a/" + library
            alignments = elf_load_alignments(archive.read(path))
            if not alignments or any(value < 16_384 for value in alignments):
                raise ValueError("原生库16KiB对齐未满足：" + library)
            native[library] = alignments
        if any(name.startswith("lib/") and not name.startswith("lib/arm64-v8a/") for name in names):
            raise ValueError("正式包应仅包含ARM64原生库")
        profiles = [name for name in names if name.startswith("assets/dexopt/") and name.endswith(".prof")]
        if not profiles or any(not archive.read(name).startswith(b"pro\x00") for name in profiles):
            raise ValueError("缺少已编译启动Profile")
        forbidden_paths = ("litert", "local-models", "support_wechat_reward")
        if any(any(part in name.lower() for part in forbidden_paths) for name in names):
            raise ValueError("发现已删除功能的打包路径")
        forbidden_dex = (b"EtaVoiceAssistActivity", b"com/google/ai/edge/litertlm", b"LocalModelsScreen",
                         b"qr.alipay.com", b"afdian.com/a/Mcool520", b"alipays://platformapi/startapp")
        for name in names:
            if name.endswith(".dex"):
                data = archive.read(name)
                hits = [needle.decode() for needle in forbidden_dex if needle in data]
                if hits:
                    raise ValueError("发现已删除功能的DEX残留：" + ", ".join(hits))
        android_manifest = archive.read("AndroidManifest.xml")
        if "EtaVoiceAssistActivity".encode("utf-16-le") in android_manifest or b"EtaVoiceAssistActivity" in android_manifest:
            raise ValueError("Manifest中仍有旧助理Activity入口")
    return {"apk": str(apk), "bytes": apk.stat().st_size, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "modelSha256": MODEL_SHA256, "nativeLoadAlignments": native,
            "startupProfiles": profiles, "removedFeaturesAbsent": True}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.apk), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
