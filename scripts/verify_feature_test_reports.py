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
    "EtaDatabaseMigrationTest",
    "SubagentModelSelectionTest", "SubagentModelSelectionContractTest", "SubagentModelDispatchTest", "ProviderComponentsTest", "DiagnosticsAutoExportPolicyTest",
    "AgentMultiModelFanoutTest", "FanoutReadOnlyTest", "SessionSamplingOverrideTest",
    "SessionSamplingInjectionRequestTest", "ConversationCheckpointIntegrityTest",
    "SkillFileNamesTest", "PublicGitHubSkillSourceTest", "SkillPackageInstallerTest", "SkillRuntimeTest", "SkillResourceReaderTest", "GitHubSkillRepositoryParserTest",
    "McpTransportRobustnessTest", "McpCatalogIntegrityTest", "McpSnapshotLifecycleTest",
    "McpRefreshConcurrencyTest", "McpQueryAuthorizationTest", "McpConnectorDraftTest",
    "McpCredentialUpdateTest", "McpConnectorDiscoveryTest",
    "EtaSettingsGlassTest", "EtaSettingsControlsTest", "OnboardingRetirementTest",
    "ConnectorCatalogTest", "ConnectorEndpointValidatorTest", "PluginCatalogTest",
    "PromptBoundaryOverviewTest",
}
REQUIRED_CASES = {
    ("McpTransportRobustnessTest", "sseStreamIgnoresKeepAliveCommentsAndHonoursEventIdRetryFields"),
    ("McpCatalogIntegrityTest", "pageLimitFailsInsteadOfPublishingIncompleteCatalog"),
    ("McpCatalogIntegrityTest", "toolLimitFailsInsteadOfSilentlyDroppingLaterTools"),
    ("McpCatalogIntegrityTest", "repeatedCursorFailsWithoutReturningPartialToolsOrLegacyFallback"),
    ("McpSnapshotLifecycleTest", "incompletePaginatedRefreshPreservesSavedCatalogAndSelections"),
    ("McpRefreshConcurrencyTest", "refreshUsesSelectionChangedWhileDiscoveryWasWaiting"),
    ("McpRefreshConcurrencyTest", "refreshPreservesCurrentDisabledStateNameOrderAndCreationTime"),
    ("McpRefreshConcurrencyTest", "runSnapshotOmitsServerDisabledDuringItsRefresh"),
    ("McpRefreshConcurrencyTest", "newBearerCredentialDiscardsOldAnonymousDiscoveryWithoutClearingToken"),
    ("McpRefreshConcurrencyTest", "delayedEmptyConfirmationRejectsChangedCredentialsAndKeepsCatalog"),
    ("McpQueryAuthorizationTest", "modernDiscoveryAndCallReplaceDuplicateKeyAndEncodeUnicodeWithoutBearer"),
    ("McpQueryAuthorizationTest", "legacyInitializeNotificationDiscoveryCallAndDeleteAllUseQueryAk"),
    ("McpQueryAuthorizationTest", "sameOrigin307And308PreserveQuerySecretAndReplaceRedirectSuppliedValues"),
    ("McpQueryAuthorizationTest", "crossOriginRedirectNeverSendsQueryCredentialToForeignEndpoint"),
    ("McpQueryAuthorizationTest", "missingStoredSecretFailsBeforeUsingAnInlineUrlKey"),
    ("McpQueryAuthorizationTest", "queryTemplateDraftRequiresSeparateKeyAndSupportsSavedCredentialRotation"),
    ("McpConnectorDraftTest", "creatingQueryServerWithoutAKeyNeverDowngradesToAnonymous"),
    ("McpConnectorDraftTest", "creatingBearerServerWithoutATokenStillAllowsAnonymousEndpoints"),
    ("McpQueryAuthorizationTest", "queryAuthenticationErrorsDoNotEchoSecretsOrEndpointInMessagesAndCauses"),
    ("McpQueryAuthorizationTest", "repositoryEncryptsBothQuerySecretsStripsUrlAndPreservesAuthThroughUpdatesAndSerialization"),
    ("McpQueryAuthorizationTest", "refreshIdentityUsesCleanSavedUrlAndSecretAndRejectsRotatedQueryCredential"),
    ("McpConnectorDraftTest", "selectingDifferentTemplateClearsTokenAndAppliesItsDefaults"),
    ("McpConnectorDraftTest", "selectingSameTemplatePreservesEditedNameUrlAndToken"),
    ("McpConnectorDraftTest", "queryTemplatesKeepTheirAuthenticationWhenEnteringAndReenteringKeys"),
    ("McpCredentialUpdateTest", "clearingQueryCredentialsPreservesTypeForLaterRefill"),
    ("McpConnectorDiscoveryTest", "customEntryRemainsReachableInEitherScopeEvenWhenSearchHasNoResults"),
    ("EtaSettingsGlassTest", "settingsMaterialDoesNotLeakOutsideItsPageAndNestedPagesReuseTheirPalette"),
    ("EtaSettingsGlassTest", "changingBlurPreferenceReplacesSampledMaterialWithFallbackWithoutLosingContent"),
    ("EtaSettingsGlassTest", "blurDisabledUsesHighContrastFallbackInBothColorSchemes"),
    ("EtaSettingsGlassTest", "unavailableRuntimeShaderUsesFallbackEvenWhenBlurPreferenceIsEnabled"),
    ("EtaSettingsGlassTest", "preferenceThemeRetainsAnExplicitMonetPrimary"),
    ("EtaSettingsControlsTest", "glassSwitchExposesSwitchRoleStateAndExactlyOneCallbackPerAccessibleClick"),
    ("EtaSettingsControlsTest", "disabledGlassSwitchKeepsItsStateAndIgnoresPhysicalTaps"),
    ("EtaSettingsControlsTest", "switchPreferenceHasOneAccessibleToggleAndRowAndThumbEachToggleOnlyOnce"),
    ("EtaSettingsControlsTest", "longTextAtTwoHundredPercentFontScaleWrapsWithoutClippingOrCoveringTheSwitchInEitherDirection"),
    ("EtaSettingsControlsTest", "glassSwitchThumbMirrorsItsRenderedPositionInRtlForBothStates"),
    ("OnboardingRetirementTest", "oldSettingsRestoreIgnoresTourStateAndPreservesActivePreferences"),
    ("OnboardingRetirementTest", "tutorialSearchIsEmptyWhileAppearanceAndBackupRemainDiscoverable"),
    ("OnboardingRetirementTest", "removedTutorialHostsAndGestureOverlaysAreAbsentFromTheRuntime"),
    ("ConnectorCatalogTest", "endpointsAreHttpsWhenProvided"),
    ("PluginCatalogTest", "builtinSkillManifestListsEveryBundledSkillWithDistinctId"),
    ("DiagnosticsAutoExportPolicyTest", "failureBacksOffAWholeWeekInsteadOfOneMinute"),
    ("DiagnosticsAutoExportPolicyTest", "successfulFileAdvancesTheWeeklyScheduleEvenAfterAnOldFailure"),
    ("DiagnosticsAutoExportPolicyTest", "unwritablePreferredDirectoryFallsBackAndPruneKeepsTheNewestFour"),
    ("ProviderComponentsTest", "modelEditorSaverKeepsLongInvalidPromptWithoutDuplicatingCatalogMetadata"),
    ("SubagentModelSelectionContractTest", "duplicateApiNamesHaveDistinctSelectorsAndRejectTheBareName"),
    ("SubagentModelSelectionContractTest", "emptyAllowListOffersAllEnabledModelsButStaleGrantsDoNot"),
    ("SubagentModelSelectionContractTest", "disabledProviderOrModelDoesNotTransferItsGrantToTheOtherProvider"),
    ("SubagentModelDispatchTest", "ambiguousLegacyGrantAndUnknownRequestsNeverFallBackToPrimary"),
    ("SubagentModelDispatchTest", "duplicateAuthorizedNamesRequireAndHonorTheExactSelectorFromTheSchema"),
    ("LinuxApkAnalysisInstallerTest", "etaReverseUsesPinnedGuestToolsAndIsAHeavyCommand"),
    ("LinuxApkAnalysisInstallerTest", "apkProfilePublishesMarkerOnlyAfterSuccessfulProbe"),
    ("LinuxApkAnalysisInstallerTest", "cancellingApkProfileVerificationRollsBackWithoutPublishingMarker"),
    ("LinuxApkAnalysisInstallerTest", "rootlessProfileRollsBackToLegacyCommandsWithoutLeavingEtaReverseLink"),
    ("LinuxApkAnalysisInstallerTest", "legacyApkProfileAndExactAbsoluteLinksRemainUninstallable"),
    ("EtaReverseSkillPackageTest", "optionalReversePackageImportsThroughTheExistingUserSkillInstaller"),
    ("EtaReverseSkillPackageTest", "reverseWorkflowIsOptionalAndDoesNotSeedAnEnabledBuiltinOnUpgrade"),
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


# 该平台分支原本只用于 POSIX 专属的 unlink-open-file 契约测试；那个测试类已随
# 「模型级提示词」一起下线，分支随之删除，避免在非 Windows 环境注入无效的门禁项。


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
            name = suite.get("name").rsplit(".", 1)[-1]
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
                case = test.get("name").split("[", 1)[0]
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
