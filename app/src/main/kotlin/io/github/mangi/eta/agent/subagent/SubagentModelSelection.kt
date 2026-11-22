package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.data.model.ProviderModel
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.SubagentModelGrant
import io.github.mangi.eta.data.model.SubagentSettings
import io.github.mangi.eta.data.model.resolveSubagentModelGrant
import io.github.mangi.eta.data.model.subagentModelSelectionId
import io.github.mangi.eta.data.repository.RuntimeConfigRepository

/** The schema and dispatcher must use the same model identities and grant interpretation. */
internal data class GrantedSubagentModel(
    val resolved: ProviderModel,
    val selector: String,
    val allowedEfforts: List<String>,
) {
    val uuid: String get() = resolved.model.id
    val apiId: String get() = resolved.model.modelId
    val selectionId: String get() = subagentModelSelectionId(resolved.provider.id, uuid)

    fun option(): SubagentModelOption = SubagentModelOption(
        modelId = selector,
        apiId = apiId,
        providerName = resolved.provider.name,
        efforts = allowedEfforts,
        contextWindow = resolved.model.effectiveContextWindow,
        supportsThinking = RuntimeConfigRepository.reasoningCapabilities(resolved.provider, resolved.model) != null,
    )
}

internal object SubagentModelSelection {
    fun resolve(providers: List<ProviderSetting>, settings: SubagentSettings): List<GrantedSubagentModel> {
        if (!settings.allowModelSelection) return emptyList()
        val grants = settings.effectiveGrants.ifEmpty {
            // An actually empty allow-list means all enabled models; stale non-empty grants do not.
            if (settings.allowedGrants.isNotEmpty() || settings.allowedModelIds.isNotEmpty()) return emptyList()
            providers.filter { it.isEnabled }.flatMap { provider ->
                provider.models.filter { it.isEnabled }.map { model ->
                    SubagentModelGrant(subagentModelSelectionId(provider.id, model.id))
                }
            }
        }
        val resolved = grants.mapNotNull { grant ->
            providers.resolveSubagentModelGrant(grant.modelId)?.let { grant to it }
        }.distinctBy { (_, entry) -> subagentModelSelectionId(entry.provider.id, entry.model.id) }
        val duplicateApiIds = resolved.groupingBy { it.second.model.modelId.lowercase() }
            .eachCount().filterValues { it > 1 }.keys
        return resolved.map { (grant, entry) ->
            val selector = if (entry.model.modelId.lowercase() in duplicateApiIds) {
                subagentModelSelectionId(entry.provider.id, entry.model.id)
            } else {
                entry.model.modelId
            }
            val selectable = RuntimeConfigRepository.reasoningCapabilities(entry.provider, entry.model)
                ?.selectableEfforts?.map { it.wireValue }.orEmpty()
            GrantedSubagentModel(
                resolved = entry,
                selector = selector,
                allowedEfforts = if (grant.allowedEfforts.isEmpty()) selectable
                    else selectable.filter { it in grant.allowedEfforts },
            )
        }
    }

    fun select(models: List<GrantedSubagentModel>, requested: String): GrantedSubagentModel? {
        val id = requested.trim()
        val exact = models.filter { it.selector == id || it.selectionId == id || it.uuid == id }
        if (exact.isNotEmpty()) return exact.singleOrNull()
        // Legacy API names are accepted only when they identify one authorized entry.
        return models.filter { it.apiId.equals(id, ignoreCase = true) }.singleOrNull()
    }
}
