#!/usr/bin/env python3
"""Require actual passing feature suites; an unmatched Gradle --tests filter is never proof."""
from __future__ import annotations

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

REQUIRED_SUITES = {
    "UserMessageActionAvailabilityTest", "UserMessageCopyTest", "MemoryReviewGuardTest",
    "MemoryReviewLifecycleTest", "ApprovalResolutionTest", "ExperienceReviewPolicyTest",
    "SettingsSectionsTest", "MemoryRecallProjectionTest", "NoteStoreTest",
    "QuickTileServiceTest", "UapiWeatherCacheTest", "WeatherWidgetProviderTest",
    "NfcAutomationTest", "NfcTagReceiverTest", "AutomationRuleDryRunTest",
    "KnowledgeLibraryExecutionTest", "KnowledgeScreenContractTest", "KnowledgeCitationRouteTest",
    "AgentMultiModelFanoutTest", "FanoutReadOnlyTest", "SessionSamplingOverrideTest",
    "SessionSamplingInjectionRequestTest", "AgentRuntimeWireTest", "ConversationCheckpointIntegrityTest",
    "XingchiCompatibilityTest", "XingchiWireContractTest", "GatewayAccessFailureTest", "ProviderRepositoryTest",
}
REQUIRED_CASES = {
    ("AgentRuntimeWireTest", "sessionSamplingRoundTripsAndRejectsOutOfRangeWireValues"),
    ("ProviderRepositoryTest", "xingchiPresetsPersistAcrossDatabaseReopenWithoutChangingAnotherSameHostConnection"),
    ("ProviderRepositoryTest", "xingchiUnsavedDraftLeavesStoredPresetUnchangedAndCustomIdentitySurvivesReopen"),
    ("KnowledgeLibraryExecutionTest", "nativeAndroidFileBackendIndexesAndSearchesWithoutRootOnSupportedUnixHosts"),
}


def verify(directory: Path) -> list[str]:
    problems: list[str] = []
    seen: set[str] = set()
    cases: set[tuple[str, str]] = set()
    files = sorted(directory.glob("TEST-*.xml"))
    if not files:
        return [f"No JUnit XML reports in {directory}"]
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as error:
            problems.append(f"Unreadable JUnit report {path.name}: {error}")
            continue
        suites = [root] if root.tag == "testsuite" else root.findall(".//testsuite")
        for suite in suites:
            name = suite.get("name", "").rsplit(".", 1)[-1]
            if name not in REQUIRED_SUITES:
                continue
            seen.add(name)
            tests = suite.findall("testcase")
            if not tests:
                problems.append(f"{name}: discovered no executed test cases")
            for attribute in ("failures", "errors", "skipped"):
                if int(suite.get(attribute, "0")):
                    problems.append(f"{name}: {attribute}={suite.get(attribute)}")
            for test in tests:
                case = test.get("name", "").split("[", 1)[0]
                if any(test.find(tag) is not None for tag in ("failure", "error", "skipped")):
                    problems.append(f"{name}.{case}: not a passing execution")
                else:
                    cases.add((name, case))
    problems.extend(f"Missing required suite: {name}" for name in sorted(REQUIRED_SUITES - seen))
    problems.extend(f"Missing passing case: {name}.{case}" for name, case in sorted(REQUIRED_CASES - cases))
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", nargs="?", type=Path, default=Path("app/build/test-results/testDebugUnitTest"))
    arguments = parser.parse_args()
    problems = verify(arguments.directory)
    if problems:
        print("Required feature verification failed:", file=sys.stderr)
        for problem in problems:
            print("- " + problem, file=sys.stderr)
        return 1
    print(f"Verified {len(REQUIRED_SUITES)} required suites and {len(REQUIRED_CASES)} mandatory integration cases without skips.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
