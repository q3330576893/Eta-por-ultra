package io.github.mangi.eta.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.SettingsSection

/**
 * 设置项搜索索引：一个分组一条。
 *
 * 可搜文本由三部分组成，全部小写后比对：
 * 1. [labels]：该分组在设置页上真实显示的标题（字符串资源）。按当前语言取文案，
 *    所以「页面上看得见的标题」一定搜得到，不会再出现「页面上有、搜索里没有」；
 * 2. [labelsRaw]：少数没走字符串资源的硬编码标题，照抄文案；
 * 3. [keywords]：别名、同义词，以及二级页里的功能名词——搜具体功能也能落到入口分组。
 *
 * 每个 SettingsSection 恰好登记一条；页面与过滤结果统一使用枚举优先级，匹配结果直接决定条目可见性。
 */
internal data class SettingsSearchEntry(
    val section: SettingsSection,
    val labels: List<Int> = emptyList(),
    val labelsRaw: List<String> = emptyList(),
    val keywords: String = "",
)

internal val SETTINGS_SEARCH_INDEX = listOf(
    SettingsSearchEntry(
        section = SettingsSection.MODELS_CONNECTIONS,
        labels = listOf(
            R.string.settings_priority_models_connections,
            R.string.settings_priority_provider_title,
            R.string.settings_priority_current_model,
            R.string.ui_deep_thinking_enabled_by_default_c032d6,
            R.string.route_github_account,
        ),
        keywords = "llm 提供商 供应商 模型 当前模型 服务商 api 密钥 key 接口 地址 baseurl 思考 深度思考 推理 reasoning 默认启用思考 温度 上下文长度 账号 连接 登录 github 令牌 token oauth 凭据 授权 断开",
    ),
    SettingsSearchEntry(
        section = SettingsSection.ASSISTANT_VOICE,
        labels = listOf(
            R.string.settings_priority_assistant_voice,
            R.string.speech_settings_title,
            R.string.settings_priority_voice_summary,
            R.string.lingscreen_entry_title,
            R.string.weather_settings_title,
            R.string.weather_settings_entry_summary,
        ),
        keywords = "助手 assistant 语音 朗读 播报 tts asr 音色 语速 音调 发音人 识别 自动播报 聆听 停顿 自动发送 本地 离线 vosk 麦克风 灵屏 屏幕 小窗 天气 天气预报 定位 当前位置 常用地点 城市 坐标 温度 预报 气象 地区 地点 询问天气",
    ),
    SettingsSearchEntry(
        section = SettingsSection.APPEARANCE_DATA,
        labels = listOf(
            R.string.settings_priority_appearance_data,
            R.string.appearance_title,
            R.string.settings_language,
            R.string.data_backup_title,
            R.string.data_usage_title,
            R.string.settings_priority_reset_tour,
        ),
        keywords = "通用 外观 主题 深色 浅色 夜间 跟随系统 monet 取色 色彩 强调色 纯黑 模糊 顶栏 横移 返回 手势 预测性返回 界面缩放 字号 语言 备份 导入 导出 恢复 自动备份 周期 保留份数 备份格式 文件夹 数据占用 清理 数据库 缓存 大小 存储 新手引导",
    ),
    SettingsSearchEntry(
        section = SettingsSection.TOOLS_EXTENSIONS,
        labels = listOf(
            R.string.settings_priority_tools_extensions,
            R.string.settings_tools_list,
            R.string.ui_enable_web_browsing_tools_8b6b03,
            R.string.web_tools_settings_title,
            R.string.ui_linux_tool_environment_314d22,
            R.string.automation_title,
            R.string.route_subagents,
            R.string.route_skills,
            R.string.route_mcp_servers,
        ),
        keywords = "工具 工具能力 工具档 精简 standard 完整 全部 mcp 网页 搜索 读取 浏览器 browser 抓取 fetch linux 发行版 debian 环境 apk 分析 工作区 共享文件夹 扩展 自动化 自动任务 定时 计划 触发 automation 子智能体 子代理 subagent skills 技能 服务器 server",
    ),
    SettingsSearchEntry(
        section = SettingsSection.PROMPTS_MEMORY,
        labels = listOf(
            R.string.settings_priority_prompts_memory,
            R.string.ui_memory_b55ff5,
            R.string.settings_auto_compaction,
            R.string.prompt_studio_section,
            R.string.prompt_studio_title,
            R.string.sidebar_characters,
        ),
        keywords = "人格 提示词 提示词工坊 工坊 工具 边界 系统提示词 自定义提示词 版本 历史 快照 请求快照 预设 导入 导出 完整性 当前生效 注入 角色 角色卡 人设 对比 记忆 memory 记忆注入 上下文 自动压缩 压缩 上下文长度",
    ),
    SettingsSearchEntry(
        section = SettingsSection.PERMISSIONS_DEVICES,
        labels = listOf(
            R.string.settings_priority_permissions_devices,
            R.string.ui_floating_window_permissions_076b77,
            R.string.ui_accessibility_enhancement_tools_8fd257,
            R.string.ui_enforce_accessibility_55e838,
            R.string.lingscreen_fluid_pinned,
            R.string.lingscreen_fluid_downgrade,
            R.string.ui_enable_device_direct_tools_e2d595,
            R.string.ui_allow_reading_of_sensitive_device_information_feaec0,
            R.string.ui_allow_sensitive_device_operation_3d42ea,
            R.string.ui_enable_terminal_file_tools_18bb43,
        ),
        keywords = "权限 授权 设备能力 无障碍 增强工具 强制 保持 悬浮窗 悬浮 通知 提醒 流体云 常驻 降级 胶囊 状态栏 实时活动 live activity 后台 自启动 电量 设备 直达 敏感 设备信息 设备操作 终端 shell 命令行 命令 文件 file",
    ),
    SettingsSearchEntry(
        section = SettingsSection.SYSTEM_INTEGRATION,
        labels = listOf(
            R.string.settings_priority_system_integration,
            R.string.capability_enhancements,
            R.string.ui_system_assistant_takes_over_f46043,
            R.string.ui_eta_system_assistant_003e9b,
            R.string.ui_long_press_the_power_button_1958d0,
            R.string.ui_automatically_set_default_assistant_f86963,
            R.string.ui_xiaobu_xiaoai_compatible_entrance_ae918a,
            R.string.ui_enable_vendor_assistant_custom_models_c8e465,
            R.string.ui_only_take_over_with_agent_prefix_d17556,
        ),
        keywords = "系统集成 系统增强 能力增强 root 授权 超级用户 权限 magisk kernelsu ksu 框架 lsposed xposed 模块 作用域 语音唤醒 系统化 系统应用 gemini 一圈即搜 无障碍保护 受保护数据 系统助手 接管 助手 assistant 数字助理 默认助理 电源键 长按 语音助手 唤醒 唤醒词 默认应用 拨号 厂商助手 兼容 入口 小布 小爱 超级小爱 vivo 蓝心 蓝心小v oppo 自定义模型 前缀 agent 前缀 语音入口 侧键",
    ),
    SettingsSearchEntry(
        section = SettingsSection.DIAGNOSTICS,
        labels = listOf(
            R.string.settings_priority_diagnostics,
            R.string.settings_self_check_title,
            R.string.settings_diagnostics_title,
            R.string.settings_test_privacy_title,
            R.string.diag_patrol_group_title,
            R.string.diag_patrol_title,
        ),
        keywords = "诊断 维护 自检 体检 一致性 诊断与日志 日志 崩溃 诊断包 导出 分享 极致自动排查 自动排查 巡检 排查 定时巡检 周期 保留 份数 提醒 异常 报告 测试 隐私 同意 训练 撤回",
    ),
    SettingsSearchEntry(
        section = SettingsSection.ABOUT_DOWNLOADS,
        labels = listOf(
            R.string.settings_priority_about_downloads,
            R.string.about_group_title,
            R.string.about_email_title,
            R.string.ui_about_feedback_title,
            R.string.ui_about_github_star_title,
        ),
        keywords = "关于 下载 安装包 apk 正式 release 版本 版本号 更新 历史 开源 许可 协议全文 反馈 问题 报错 github star 源代码 仓库 点赞",
    ),
)

/** 把每个分组「能搜到的文本」预拼成小写串；标题按当前语言取，随语言切换重建。 */
@Composable
internal fun rememberSettingsSearchHaystacks(): Map<SettingsSection, String> {
    val resources = LocalResources.current
    return SETTINGS_SEARCH_INDEX.associate { entry ->
        entry.section to buildString {
            append(entry.keywords.lowercase())
            entry.labels.forEach { append('\u0000').append(resources.getString(it).lowercase()) }
            entry.labelsRaw.forEach { append('\u0000').append(it.lowercase()) }
        }
    }
}
