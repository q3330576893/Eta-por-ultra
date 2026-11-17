package io.github.mangi.eta.agent.planning

import io.github.mangi.eta.agent.subagent.SubagentProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPlanSubagentScopeTest {
    @Test fun wildcardAndBuiltinChildrenCannotAccessTheParentRunPlan() {
        val wildcard = SubagentProfile(
            name = "custom", summary = "custom", whenToUse = "test", toolHighlights = emptyList(),
            roleInstruction = "test", explicitTools = setOf("*"), allowScreenTools = true,
        )
        for (profile in listOf(SubagentProfile.EXPLORE, SubagentProfile.CODER, SubagentProfile.PLAN, wildcard)) {
            assertFalse(profile.name, profile.isToolAllowed("get_plan"))
            assertFalse(profile.name, profile.isToolAllowed("update_plan"))
        }
        assertTrue(wildcard.isToolAllowed("app_playbooks_list"))
    }
}
