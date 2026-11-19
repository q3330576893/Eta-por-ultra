package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.terminal.FileToolSearch
import org.json.JSONArray
import org.json.JSONObject

internal object AgentFileToolCatalog {
    val names = setOf(
        "read_file", "write_file", "edit_file", "stat_file", "list_directory", "glob_files", "grep_files",
        "index_file_to_kb", "search_kb",
    )

    fun appendTo(tools: JSONArray) {
        tools.put(tool("read_file", "读取 UTF-8 文本。已知路径直接读取；返回的 next_offset_bytes 是准确续读位置，续读同时传 expected_revision。支持 start_line 定位；超长行可分段，二进制请使用对应媒体工具。", properties()
            .put("path", path())
            .put("offset_bytes", integer("字节起点，默认 0；续读使用上次 next_offset_bytes，不要自行推算。", 0))
            .put("max_bytes", integer("单次文本字节预算，默认 16000；为兼容旧调用接受更大值，但实际最多返回 16000 字节。", 4, 262144))
            .put("start_line", integer("可选的 1 起始行号，按行读取时 offset_bytes 应省略或为 0；远距离定位受扫描预算限制，检查 start_line_reached。", 1))
            .put("max_lines", integer("最多返回多少行，默认 200；超长单行也受字节预算限制。", 1, 2000))
            .put("expected_revision", revision()), listOf("path")))
        tools.put(tool("write_file", "创建、完整覆盖或追加 UTF-8 文件；会创建父目录。局部修改请用 edit_file。expected_revision 可防止覆盖已变化的文件；atomic 说明后端是否原子替换。", properties()
            .put("path", path()).put("content", text("完整文件内容或追加内容，UTF-8 编码后最多 512 KiB。", 524288))
            .put("append", JSONObject().put("type", "boolean").put("default", false))
            .put("expected_revision", revision()), listOf("path", "content")))
        tools.put(tool("edit_file", "精确替换不超过 512 KiB 的 UTF-8 文件中的 old_text。默认必须唯一匹配；未找到或多处匹配时不写入，应重新读取或补充上下文。写入前再次检查旧内容；atomic 不代表与外部程序互斥。", properties()
            .put("path", path()).put("old_text", text("必须与文件中旧内容完全匹配，不可为空。", 524288).put("minLength", 1))
            .put("new_text", text("替换内容，空字符串表示删除匹配片段。", 524288))
            .put("replace_all", JSONObject().put("type", "boolean").put("default", false))
            .put("expected_revision", revision()), listOf("path", "old_text", "new_text")))
        tools.put(tool("stat_file", "查询已知文件或目录的规范路径、类型、大小及版本，不读取正文。", properties()
            .put("path", path()), listOf("path")))
        tools.put(tool("list_directory", "分页列出直接子项，返回结构化条目与 next_offset。下一页使用相同路径、next_offset 和 expected_revision；has_more=false 才表示列举完成。", properties()
            .put("path", path()).put("show_hidden", JSONObject().put("type", "boolean").put("default", false))
            .put("limit", integer("原始目录单页条目数，默认 80，隐藏项过滤后可能不足此数。", 1, 200))
            .put("offset", integer("目录枚举位置，默认 0；续页使用 next_offset。", 0))
            .put("expected_revision", revision()), emptyList()))
        tools.put(tool("glob_files", "按路径模式递归查找工作目录内的文件。支持 *、?、**，默认跳过隐藏目录且不追踪目录链接。结果有界；继续查询使用原样 cursor，partial=true 时不能断言已搜索全部文件。", properties()
            .put("path", path()).put("pattern", text("相对于搜索目录的模式，例如 **/*.json。", 500).put("minLength", 1))
            .put("limit", integer("最多返回条目数，默认 80。", 1, 200))
            .put("cursor", text("上次查询返回的不透明续查游标，保持所有查询条件不变。", FileToolSearch.MAX_CURSOR_CHARS))
            .put("include_hidden", JSONObject().put("type", "boolean").put("default", false)), listOf("pattern")))
        tools.put(tool("grep_files", "在工作目录的 UTF-8 文本中搜索字面文本，返回路径、行号与匹配行。默认区分大小写；不依赖系统 rg。按条目和字节预算分段扫描，可通过游标续查；二进制和不可访问项会被跳过并报告不完整范围。", properties()
            .put("path", path()).put("query", text("要查找的字面文本，不是正则表达式。", 500).put("minLength", 1))
            .put("glob", text("相对于目录的文件模式，默认 **/*。", 500))
            .put("limit", integer("最多返回匹配条数，默认 50。", 1, 200))
            .put("cursor", text("上次查询返回的续查游标，保持所有查询条件不变。", FileToolSearch.MAX_CURSOR_CHARS))
            .put("case_sensitive", JSONObject().put("type", "boolean").put("default", true))
            .put("include_hidden", JSONObject().put("type", "boolean").put("default", false)), listOf("query")))
        tools.put(tool("index_file_to_kb", "通过当前已授权的文件后端完整读取 UTF-8 原文，原子保存到本会话已附加的具名资料库。单文档最多 512 KiB；相同路径、环境、身份重新索引会替换旧版本。先由用户在资料库管理页创建并附加集合；多个集合时填写 collection_id。保留文件工具的环境、身份、expected_revision 与终端权限语义；关键词索引，无 embedding。", properties()
            .put("path", path()).put("expected_revision", revision())
            .put("collection_id", text("当前会话已附加的资料库 ID；仅附加一个时可省略。", 100)), listOf("path")))
        tools.put(tool("search_kb", "仅按关键词搜索用户附加到当前会话的资料库与固定来源。未选择资料时返回选择提示。返回 filename、path、revision、start_line、end_line、excerpt、citation_url 与已转义的 citation Markdown。回答引用来源时逐字复用 citation；应用会在内部来源页打开精确版本与行号，禁止臆造链接或文件路径。原文变化、删除或授权失效会移除旧索引。支持中文词片段；无 embedding、语义检索或全局搜索。", JSONObject()
            .put("query", text("关键词，支持中文与英文。", 500).put("minLength", 1))
            .put("limit", integer("最多返回命中片段数，默认 10。", 1, 20)), listOf("query")))
    }

    private fun properties(): JSONObject = JSONObject()
        .put("environment", JSONObject().put("type", "string").put("enum", JSONArray(listOf("android", "linux")))
            .put("description", "文件路径所属环境，默认 android；linux 使用设置中选定的发行版和后端，不能使用宿主 rootfs 路径代替 guest 路径。"))
        .put("identity", JSONObject().put("type", "string").put("enum", JSONArray(listOf("user", "root")))
            .put("description", "可选的宿主执行身份；Android 已确认 Root 授权时默认 root，否则 user，与终端一致。Linux 按所选后端解析，PRoot 使用 user，chroot 使用 root。"))
        .put("cwd", text("可选的工作目录；省略时与终端共用当前环境的默认工作区，实际路径见本次执行上下文。", 4096))

    private fun path(): JSONObject = text("绝对路径或相对于 cwd 的路径；仅接受文件系统路径，不接受 content URI。使用已导入文件的路径时操作的是该副本。", 4096)
    private fun revision(): JSONObject = text("上一次文件或目录结果中的 revision；不同环境和身份的版本不能混用。", 256)
    private fun text(description: String, max: Int): JSONObject = JSONObject().put("type", "string").put("description", description).put("maxLength", max)
    private fun integer(description: String, minimum: Int, maximum: Int? = null): JSONObject =
        JSONObject().put("type", "integer").put("description", description).put("minimum", minimum)
            .also { if (maximum != null) it.put("maximum", maximum) }

    private fun tool(name: String, description: String, properties: JSONObject, required: List<String>): JSONObject {
        val schema = JSONObject().put("type", "object").put("properties", properties)
            .put("additionalProperties", false).put("required", JSONArray(required))
        return AgentToolSchema.function(name, description, schema)
    }
}
