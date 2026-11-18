package io.github.mangi.eta.core

/**
 * 配置分层覆盖模型（借鉴 Mycroft 的分层配置，调研见方向三建议 3.4）。
 *
 * 优先级从低到高：[SettingsLayer.DEFAULT] < [SettingsLayer.REMOTE] < [SettingsLayer.DEVICE] < [SettingsLayer.USER]。
 *
 * 合并规则（first-wins by priority）：
 * 1. 每个配置键在每一层要么“显式设置”、要么“未设置”（Kotlin 的 null 表示未设置，
 *    与“显式设为默认值”严格区分）。
 * 2. 解析时从最高优先级层向最低层查找，取第一个显式设置的值；全部未设置时取
 *    [LayeredSetting.default]（编译期内置默认，恒不为空）。
 * 3. 重置某一层只清掉该层的显式值，低层设置立即显现；重置 DEFAULT 等于清空全部覆盖。
 * 4. [SettingsLayers.merge] 提供 Map 批量叠加版本：按优先级从低到高应用，value==null 表示该层
 *    显式“删除/重置”这个键，而不是“未设置”。
 *
 * 旧配置平滑迁移：
 * 升级前不存在分层概念，磁盘上已有的值都是“用户在设备上显式设置过的值”，
 * 统一迁移到 [SettingsLayer.USER] 层；迁移幂等——任意覆盖层已有值时保留原结构不动，
 * 因此不会把远程下发或本设备覆盖误降为用户层。
 *
 * 本文件只定义纯逻辑，不绑定任何 Repository/DataStore。全量 Repository 接入属于架构级改造，
 * 回归风险高；本轮只做基础设施 + 一个样例键 [SampleSettings.MEMORY_CORE_BUDGET_CHARS]
 * 与一处真实调用点（记忆核心注入预算 `AgentMemoryContextBuilder.coreBudgetChars`），
 * 其余配置的逐层接入报告给父代理统一协调。
 */

/** 四个配置层。数值越大优先级越高。 */
enum class SettingsLayer(val priority: Int) {
    /** 应用内置编译期默认值；恒有值、不可重置。 */
    DEFAULT(0),

    /** 远端下发配置（云控）；可被本设备与用户覆盖。 */
    REMOTE(1),

    /** 仅本设备生效的覆盖；换机不跟随。 */
    DEVICE(2),

    /** 用户显式设置的值；最高优先级。 */
    USER(3),
    ;

    companion object {
        /** 低 → 高优先级顺序，供 UI 展示与批量合并使用。 */
        val ORDER: List<SettingsLayer> = SettingsLayer.values().sortedBy { it.priority }
    }
}

/**
 * 单个配置键在四层上的取值快照。
 * [default] 恒非空；其余层 null 表示该层未显式设置。
 */
internal data class LayeredSetting<T>(
    val key: String,
    val default: T,
    val remote: T? = null,
    val device: T? = null,
    val user: T? = null,
)

internal object SettingsLayers {
    /** 解析最终生效值：从高优先级向下取第一个显式设置的值。 */
    fun <T> resolve(setting: LayeredSetting<T>): T =
        setting.user ?: setting.device ?: setting.remote ?: setting.default

    /** 最终生效值来自哪一层，便于 UI 做“精确到层”的重置。 */
    fun <T> resolvedLayer(setting: LayeredSetting<T>): SettingsLayer = when {
        setting.user != null -> SettingsLayer.USER
        setting.device != null -> SettingsLayer.DEVICE
        setting.remote != null -> SettingsLayer.REMOTE
        else -> SettingsLayer.DEFAULT
    }

    /**
     * 旧配置迁移：把升级前的值落到 USER 层。
     * 幂等——已有任意覆盖层时视为已迁移，原样返回。
     */
    fun <T> migrateLegacy(setting: LayeredSetting<T>, legacy: T?): LayeredSetting<T> {
        if (legacy == null) return setting
        val alreadyLayered =
            setting.user != null || setting.device != null || setting.remote != null
        return if (alreadyLayered) setting else setting.copy(user = legacy)
    }

    /** 设置某一层的值；value==null 等价于清掉该层（DEFAULT 层 null 时保持原值）。 */
    fun <T> setLayer(
        setting: LayeredSetting<T>,
        layer: SettingsLayer,
        value: T?,
    ): LayeredSetting<T> = when (layer) {
        SettingsLayer.DEFAULT -> if (value == null) setting else setting.copy(default = value)
        SettingsLayer.REMOTE -> setting.copy(remote = value)
        SettingsLayer.DEVICE -> setting.copy(device = value)
        SettingsLayer.USER -> setting.copy(user = value)
    }

    /** 重置某一层：只清该层覆盖；重置 DEFAULT 层会清掉全部覆盖。 */
    fun <T> resetLayer(setting: LayeredSetting<T>, layer: SettingsLayer): LayeredSetting<T> = when (layer) {
        SettingsLayer.DEFAULT -> setting.copy(remote = null, device = null, user = null)
        SettingsLayer.REMOTE -> setting.copy(remote = null)
        SettingsLayer.DEVICE -> setting.copy(device = null)
        SettingsLayer.USER -> setting.copy(user = null)
    }

    /**
     * Map 批量合并：层按优先级从低到高应用，同键高优先级覆盖低优先级。
     * value==null 表示该层显式删除该键（重置为更低层的效果）。
     */
    fun merge(vararg layers: Pair<SettingsLayer, Map<String, Any?>>): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        layers.sortedBy { it.first.priority }.forEach { (_, values) ->
            values.forEach { (key, value) ->
                if (value == null) result.remove(key) else result[key] = value
            }
        }
        return result
    }
}

/** 本轮已接入的配置样例键；后续新增键在此登记，逐层接入由父代理协调。 */
internal object SampleSettings {
    /** 记忆核心注入预算（字符数）；默认值仍由调用方按上下文窗口比例计算。 */
    const val MEMORY_CORE_BUDGET_CHARS = "memory.core_budget_chars"
}
