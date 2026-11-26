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
        if "assets/offline-asr/vosk-model-small-cn-0.22.zip" in names:
            raise ValueError("轻量包中仍打包了按需语音模型")
        if apk.stat().st_size > 15_000_000:
            raise ValueError("正式包超过15MB体积上限")
        dex_files = [entry for entry in archive.infolist() if entry.filename.endswith(".dex")]
        if not dex_files or any(entry.compress_type != zipfile.ZIP_DEFLATED for entry in dex_files):
            raise ValueError("正式包DEX未压缩")
        manifest = json.loads(archive.read("assets/offline-asr/manifest.json"))
        if manifest["archiveSha256"] != MODEL_SHA256 or manifest["archiveBytes"] != MODEL_BYTES:
            raise ValueError("模型清单身份不匹配")
        if manifest["source"] != "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip":
            raise ValueError("按需模型下载源不匹配")
        for required in ("assets/offline-asr/LICENSE-JNA.txt", "assets/offline-asr/LICENSE-vosk-Apache-2.0.txt",
                         "assets/offline-asr/NOTICE.txt"):
            if not archive.read(required):
                raise ValueError("缺少第三方声明：" + required)
        reverse_source = archive.read("assets/reverse/EtaReverse.java")
        expected_reverse = Path(__file__).resolve().parents[1] / "app/src/main/assets/reverse/EtaReverse.java"
        if not expected_reverse.is_file() or reverse_source != expected_reverse.read_bytes():
            raise ValueError("APK逆向模块源码与已验证的构建源码不一致")
        reverse_sha256 = hashlib.sha256(reverse_source).hexdigest()
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
        def contains_string(data: bytes, value: str) -> bool:
            return value.encode("utf-8") in data or value.encode("utf-16-le") in data

        removed_components = (
            "EtaVoiceAssistActivity", "MemoInboxActivity", "MemoWidgetProvider", "QuickTileService",
            "QuickSurfacesSettingsActivity", "KnowledgeLibraryActivity", "KnowledgeSourceActivity",
            "NfcTagReceiver", "NfcBindingActivity", "NfcTagActivity", "AutomationRuleDryRunActivity",
        )
        if any(contains_string(android_manifest, name) for name in removed_components):
            raise ValueError("Manifest中仍有已移除的助理或功能入口")
        for component in ("WeatherWidgetProvider", "WeatherWidgetSettingsActivity"):
            if not contains_string(android_manifest, component):
                raise ValueError("天气组件或独立设置入口缺失：" + component)
        resources = archive.read("resources.arsc")
        retired_resources = (
            "memo_title", "memo_widget_empty", "quick_surface_title", "knowledge_library_entry",
            "automation_tools_nfc_title", "automation_dry_run_entry", "automation_tools_dryrun_entry",
        )
        if any(contains_string(resources, name) for name in retired_resources):
            raise ValueError("发现已移除功能的资源残留")
        weather_resources = (
            "weather_widget_name", "weather_widget_choose_place", "weather_widget_empty",
            "weather_widget_cached_only", "weather_widget_refresh", "weather_widget_refreshing",
            "weather_widget_updated", "weather_widget_stale", "weather_widget_timeout", "weather_widget_failed",
        )
        if any(not contains_string(resources, name) for name in weather_resources):
            raise ValueError("天气组件资源不完整")
        # 模型级提示词已下线：相关资源与协议标记必须"不存在"，否则说明构建里还留着旧代码。
        retired_hook_resources = (
            "provider_model_hook_title", "provider_model_hook_body_conflict",
            "prompt_snapshot_request_hook_active",
        )
        if any(contains_string(resources, name) for name in retired_hook_resources):
            raise ValueError("已下线的模型请求提示词资源仍在 APK 中")
        # 只校验协议注入标记：`request_hook_json` 仍会作为迁移 SQL 的列名出现（34→35 加列、35→36 删列），
        # 那是升级链的必要历史，不能算作功能残留。
        retired_hook_markers = (b"<model_request_hook revision=", b"ModelRequestHookProjection")
        dex_payloads = [archive.read(entry) for entry in dex_files]
        if any(any(marker in data for data in dex_payloads) for marker in retired_hook_markers):
            raise ValueError("已下线的模型请求提示词协议标记仍在 DEX 中")
    return {"apk": str(apk), "bytes": apk.stat().st_size, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "modelSha256": MODEL_SHA256, "modelDelivery": "on-demand", "dexCompressed": True,
            "nativeLoadAlignments": native,
            "startupProfiles": profiles, "removedFeaturesAbsent": True,
            "retiredFeatureComponentsAbsent": True, "weatherWidgetRetained": True,
            "reverseHelperSourceSha256": reverse_sha256,
            "modelRequestHookRetired": True,
            "retiredHookResourcesAbsent": list(retired_hook_resources),
            "weatherResourcesPresent": list(weather_resources)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.apk), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
