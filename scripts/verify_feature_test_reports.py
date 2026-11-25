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
    "SettingsSectionsTest", "MemoryRecallProjectionTest",
    "UapiWeatherCacheTest", "WeatherWidgetProviderTest", "DailyIntentRouterTest",
    "RetiredFeaturesTest", "FullBackupRetirementTest", "ScreenObservationToolContractTest", "AgentTaskSchedulerTest", "AgentRunArchiveStoreTest",
    "AgentLocalToolsPermissionTest", "AgentSensitiveTranscriptTest", "MarkdownInlineTest",
    "LinuxApkAnalysisInstallerTest", "EtaReverseSkillPackageTest",
    "ModelRequestHookProjectionTest", "ModelRequestHookProtocolTest", "ModelRequestHookBudgetTest",
    "ModelRequestHookTest", "ModelRequestHookClientScopeTest", "AgentRuntimeRequestHookTest",
    "RuntimeConfigRequestHookTest", "ModelRequestHookPersistenceTest", "EtaDatabaseMigrationTest",
    "AgentRequestSnapshotTest", "RequestHookSnapshotUiTest",
    "SubagentModelSelectionTest", "SubagentModelSelectionContractTest", "SubagentModelDispatchTest", "ProviderComponentsTest", "ModelEditorRestorationTest", "DiagnosticsAutoExportPolicyTest",
    "AgentMultiModelFanoutTest", "FanoutReadOnlyTest", "SessionSamplingOverrideTest",
    "SessionSamplingInjectionRequestTest", "AgentRuntimeWireTest", "ConversationCheckpointIntegrityTest",
    "SkillFileNamesTest", "PublicGitHubSkillSourceTest", "SkillPackageInstallerTest", "SkillRuntimeTest", "SkillResourceReaderTest", "GitHubSkillRepositoryParserTest",
    "McpTransportRobustnessTest", "ConnectorCatalogTest", "ConnectorEndpointValidatorTest", "PluginCatalogTest",
}
REQUIRED_CASES = {
    ("McpTransportRobustnessTest", "sseStreamIgnoresKeepAliveCommentsAndHonoursEventIdRetryFields"),
    ("ConnectorCatalogTest", "endpointsAreHttpsWhenProvided"),
    ("PluginCatalogTest", "builtinSkillManifestListsEveryBundledSkillWithDistinctId"),
    ("DiagnosticsAutoExportPolicyTest", "failureBacksOffAWholeWeekInsteadOfOneMinute"),
    ("DiagnosticsAutoExportPolicyTest", "successfulFileAdvancesTheWeeklyScheduleEvenAfterAnOldFailure"),
    ("DiagnosticsAutoExportPolicyTest", "unwritablePreferredDirectoryFallsBackAndPruneKeepsTheNewestFour"),
    ("ModelEditorRestorationTest", "modelEditorRegistryRestoresTabIdentityAndAllUnsavedFields"),
    ("ModelEditorRestorationTest", "modelEditorCancelSaveAndReopenDoNotCarryDraftBetweenEntries"),
    ("ModelEditorRestorationTest", "newModelIdentityAndProviderScopeSurviveRegistryRecreation"),
    ("ProviderComponentsTest", "modelEditorSaverKeepsLongInvalidPromptWithoutDuplicatingCatalogMetadata"),
    ("SubagentModelSelectionContractTest", "selectedEntryKeepsItsHookAcrossProviderReordering"),
    ("SubagentModelSelectionContractTest", "duplicateApiNamesHaveDistinctSelectorsAndRejectTheBareName"),
    ("SubagentModelSelectionContractTest", "emptyAllowListOffersAllEnabledModelsButStaleGrantsDoNot"),
    ("SubagentModelSelectionContractTest", "disabledProviderOrModelDoesNotTransferItsGrantToTheOtherProvider"),
    ("SubagentModelDispatchTest", "dispatchUsesTheSelectedProvidersHookAfterSettingsAndDatabaseReopen"),
    ("SubagentModelDispatchTest", "ambiguousLegacyGrantAndUnknownRequestsNeverFallBackToPrimary"),
    ("SubagentModelDispatchTest", "duplicateAuthorizedNamesRequireAndHonorTheExactSelectorFromTheSchema"),
    ("AgentRuntimeWireTest", "modelRequestHookRoundTripsForAllChannelsAndDisabledDrafts"),
    ("AgentRuntimeWireTest", "escapedModelRequestHookUsesDeferredTextAndMaterializesWithoutChangingItsContent"),
    ("AgentRuntimeWireTest", "dedicatedOperationsDiscardChatHooksBeforeWireBudgetValidation"),
    ("ModelRequestHookBudgetTest", "hookReservationThatExhaustsHistoryBudgetStillTrimsWholeOldRounds"),
    ("ModelRequestHookProjectionTest", "userAndBothHooksSkipTheRoleplayDepthZeroUserMessage"),
    ("ModelRequestHookProjectionTest", "breenoUsesTheHookBearingRealUserInsteadOfSyntheticUserText"),
    ("ModelRequestHookProtocolTest", "roleplayDepthZeroKeepsHookOnRealUserAndPreservesSignedToolHistory"),
    ("EtaDatabaseMigrationTest", "migration34To35DefaultsHooksToDisabledAndPreservesModelsAndBoundConversation"),
    ("ModelRequestHookPersistenceTest", "savedHookSurvivesDatabaseReopenAndRemoteCatalogRefresh"),
    ("ModelRequestHookPersistenceTest", "invalidHookSaveLeavesThePriorDatabaseValueUnchanged"),
    ("ModelRequestHookPersistenceTest", "backupApiRoundTripRestoresEnabledAndDisabledHooksAndConversationBinding"),
    ("ModelRequestHookPersistenceTest", "legacyBackupWithoutHookFieldRestoresDisabledDefaultAndPreservesOtherData"),
    ("ModelRequestHookClientScopeTest", "manualCompactionIgnoresEnabledOversizedHook"),
    ("ModelRequestHookClientScopeTest", "replyRewriteIgnoresEnabledBlankHook"),
    ("ModelRequestHookClientScopeTest", "chatRejectsEnabledBlankHookBeforeProviderInvocation"),
    ("AgentRuntimeRequestHookTest", "reviewClearsChatHookAndSamplingWithoutMutatingTheOriginatingRequest"),
    ("RuntimeConfigRequestHookTest", "selectionConversationLookupAndDatabaseReopenUseTheSelectedModelsOwnHook"),
    ("RuntimeConfigRequestHookTest", "explicitSubagentUsesItsOwnHookWhileInheritedAndUnavailableModelsKeepTheParentSnapshot"),
    ("RequestHookSnapshotUiTest", "copyOmitsHookRowsAndKeepsRecordedSystemText"),
    ("ModelRequestHookProjectionTest", "userHookPreservesImagePartsAndSkipsObservationsAndSummaries"),
    ("ModelRequestHookProjectionTest", "successiveToolRoundsKeepOneHookAndNativeToolHistoryUnchanged"),
    ("ModelRequestHookProjectionTest", "transportRetryReusesProjectionAndUsageIncludesInjectedPrompt"),
    ("ModelRequestHookProtocolTest", "chatCompletionsSendsEachHookChannelAndPreservesMultimodalToolHistory"),
    ("ModelRequestHookProtocolTest", "responsesSendsEachHookChannelAndPreservesMultimodalToolHistory"),
    ("ModelRequestHookProtocolTest", "anthropicSendsEachHookChannelAndPreservesMultimodalToolHistory"),
    ("ModelRequestHookProtocolTest", "geminiSendsEachHookChannelAndPreservesMultimodalToolHistory"),
    ("ModelRequestHookProtocolTest", "customBodyCannotReplaceHookBearingProtocolInstructionsOrMessages"),
    ("ModelRequestHookProtocolTest", "enabledBlankHookFailsBeforeAnyProtocolHttpRequest"),
    ("LinuxApkAnalysisInstallerTest", "etaReverseUsesPinnedGuestToolsAndIsAHeavyCommand"),
    ("LinuxApkAnalysisInstallerTest", "apkProfilePublishesMarkerOnlyAfterSuccessfulProbe"),
    ("LinuxApkAnalysisInstallerTest", "cancellingApkProfileVerificationRollsBackWithoutPublishingMarker"),
    ("LinuxApkAnalysisInstallerTest", "rootlessProfileRollsBackToLegacyCommandsWithoutLeavingEtaReverseLink"),
    ("LinuxApkAnalysisInstallerTest", "legacyApkProfileAndExactAbsoluteLinksRemainUninstallable"),
    ("EtaReverseSkillPackageTest", "optionalReversePackageImportsThroughTheExistingUserSkillInstaller"),
    ("EtaReverseSkillPackageTest", "reverseWorkflowIsOptionalAndDoesNotSeedAnEnabledBuiltinOnUpgrade"),
    ("AgentRuntimeWireTest", "sessionSamplingRoundTripsAndRejectsOutOfRangeWireValues"),
    ("RetiredFeaturesTest", "weatherWidgetAndItsDedicatedSettingsRemainRegistered"),
    ("RetiredFeaturesTest", "retiredNfcRulesCannotBeCreatedMatchedOrPublished"),
    ("RetiredFeaturesTest", "upgradeAndRestoreRemoveOnlyRetiredNfcTasksAndTheirQueuedRuns"),
    ("RetiredFeaturesTest", "reconfiguredParentCannotCarryRetiredNfcRunsIntoTheModelQueue"),
    ("RetiredFeaturesTest", "recoveringRetiredRunningNfcDoesNotConsumeAnOrdinaryTasksRunBudget"),
    ("RetiredFeaturesTest", "retiredQueuedSummaryRestoresOnlyTheLastOrdinaryHistory"),
    ("FullBackupRetirementTest", "fullExportReportsFlushFailuresWithoutClosingTheCallersStream"),
    ("FullBackupRetirementTest", "fullExportOmitsRetiredSnapshotsAndKeepsWeatherAndMemory"),
    ("FullBackupRetirementTest", "pendingOldStagingCannotReviveRetiredSnapshots"),
    ("FullBackupRetirementTest", "stagingAnOldArchiveFiltersRetiredSnapshotsAndPreservesWeather"),
    ("FullBackupRetirementTest", "failedOrCancelledExtractionDoesNotPublishPartialRestore"),
    ("FullBackupRetirementTest", "failedExtractionPreservesAnAlreadyPublishedPendingRestore"),
    ("FullBackupRetirementTest", "completedExtractionReplacesOldPendingOnlyAfterSuccess"),
    ("FullBackupRetirementTest", "interruptedStagingPublicationRecoversThePriorCompleteDirectory"),
    ("FullBackupRetirementTest", "applyingNewPendingRestoreDoesNotReviveItsPreviousDirectory"),
    ("FullBackupRetirementTest", "entryBoundaryTruncationCannotReplaceACompletePendingRestore"),
    ("FullBackupRetirementTest", "corruptPrivateEntryCannotBePublished"),
    ("AgentRunArchiveStoreTest", "removeDeletesByRunIdOrHandoffId"),
    ("DailyIntentRouterTest", "retiredMemoTextIsNotMistakenForAWeatherPlaceFollowUp"),
    ("AgentTaskSchedulerTest", "refreshingRulesDoesNotReplaceAnExecutingJob"),
    ("AgentLocalToolsPermissionTest", "removedKnowledgeToolsAreNotAdvertisedOrExecutable"),
    ("AgentSensitiveTranscriptTest", "retiredKnowledgeToolHistoryRemainsRedacted"),
    ("MarkdownInlineTest", "retiredKnowledgeCitationKeepsItsLabelWithoutOpeningALink"),
}


# The Android unlink-open-file contract cannot execute on a Windows host.
# Its reader is required above on every host; Linux release CI must also run the real writer.
if sys.platform != "win32":
    REQUIRED_SUITES.add("ModelRequestHookPosixTransportTest")
    REQUIRED_CASES.add(("ModelRequestHookPosixTransportTest", "escapedHookWriterUsesUnlinkedDescriptorAndPreservesText"))


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
