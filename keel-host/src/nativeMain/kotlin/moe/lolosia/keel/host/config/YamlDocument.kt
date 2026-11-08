package moe.lolosia.keel.host.config

import com.charleskorn.kaml.*
import kotlinx.serialization.json.*

/**
 * 一份 YAML 文件的「解析树 + 原始文本」视图。
 *
 * 读取用 KAML 解析成 [JsonElement]；写入**不回写整棵树**，而是在原文本上
 * 按路径替换标量、插入缺失键，从而保留注释、空行与键顺序。
 * 对象 / 序列整体替换时块内部的注释无法保留（已知限制）。
 */
internal class YamlDocument private constructor(
    private val text: String,
    val root: JsonElement,
) {

    /**
     * 把合并后的 [merged] 写回原文。
     *
     * @throws UnsupportedOperationException 被修改的节点是流式集合、多行标量或锚点 / 别名
     */
    fun render(merged: JsonElement): String {
        if (merged !is JsonObject) return renderWhole(merged)

        val hadTrailingNewline = text.isEmpty() || text.endsWith("\n")
        val lines = if (text.isEmpty()) mutableListOf() else text.split('\n').toMutableList()
        if (hadTrailingNewline && lines.isNotEmpty() && lines.last().isEmpty()) {
            lines.removeAt(lines.lastIndex)
        }

        val scope = parseLines(lines)
        val oldRoot = root as? JsonObject ?: JsonObject(emptyMap())
        val edits = mutableListOf<Edit>()
        applyObject(oldRoot, merged, scope, lines, edits)
        applyEdits(lines, edits)

        val rendered = lines.joinToString("\n")
        return if (hadTrailingNewline) "$rendered\n" else rendered
    }

    private class Node(
        val path: String,
        val key: String,
        val indent: Int,
        val line: Int,
        var kind: Kind,
        var end: Int = line + 1,
        val valueStart: Int = -1,
        val valueEnd: Int = -1,
        val children: MutableList<Node> = mutableListOf(),
        val unsupported: String? = null,
    )

    private enum class Kind { SCALAR, MAP, LIST, FLOW, BLOCK_SCALAR, UNKNOWN }

    private class Scope(val nodes: List<Node>, val indent: Int, val insertAt: Int)

    /** 用 [lines] 替换原文的第 [start] 行到 [end]（不含）；start == end 表示插入。 */
    private class Edit(val start: Int, val end: Int, val lines: List<String>)

    // ---- 解析 ----

    private fun parseLines(lines: List<String>): Scope {
        val first = lines.indexOfFirst { isContent(it) }
        if (first < 0) return Scope(emptyList(), 0, lines.size)
        val indent = indentOf(lines[first])
        val nodes = mutableListOf<Node>()
        val end = parseMap(lines, first, indent, "", nodes)
        return Scope(nodes, indent, end)
    }

    private fun parseMap(
        lines: List<String>,
        start: Int,
        indent: Int,
        parentPath: String,
        out: MutableList<Node>,
    ): Int {
        var i = start
        while (i < lines.size) {
            val raw = lines[i]
            if (!isContent(raw)) {
                i++
                continue
            }
            if (indentOf(raw) != indent) break
            if (raw.trimStart().startsWith("-")) break

            val node = parseKeyLine(i, raw, parentPath) ?: break
            node.end = blockEnd(lines, i, indent)
            out += node

            if (node.kind == Kind.MAP) {
                val childIndex = nextContent(lines, i + 1, node.end)
                if (childIndex >= 0 && indentOf(lines[childIndex]) > indent) {
                    if (lines[childIndex].trimStart().startsWith("-")) {
                        node.kind = Kind.LIST
                    } else {
                        parseMap(lines, childIndex, indentOf(lines[childIndex]), node.path, node.children)
                    }
                }
            }
            i = node.end
        }
        return i
    }

    private fun parseKeyLine(lineIndex: Int, raw: String, parentPath: String): Node? {
        val indent = indentOf(raw)
        val content = raw.substring(indent)
        val colon = keyColon(content)
        if (colon <= 0) return null

        val key = unquote(content.substring(0, colon).trim())
        if (key.isEmpty()) return null
        val path = if (parentPath.isEmpty()) key else "$parentPath.$key"

        val restStart = colon + 1
        val restContent = content.substring(restStart)
        val leading = restContent.length - restContent.trimStart().length
        val valueColumn = indent + restStart + leading
        val rest = restContent.trim()

        if (rest.isEmpty() || rest.startsWith("#")) return Node(path, key, indent, lineIndex, Kind.MAP)
        if (rest.startsWith("&") || rest.startsWith("*") || rest.startsWith("!")) {
            return Node(path, key, indent, lineIndex, Kind.UNKNOWN, unsupported = "锚点 / 别名 / 自定义 tag")
        }
        if (rest.startsWith("|") || rest.startsWith(">")) {
            return Node(path, key, indent, lineIndex, Kind.BLOCK_SCALAR, unsupported = "多行标量")
        }
        if (rest.startsWith("{") || rest.startsWith("[")) {
            return Node(path, key, indent, lineIndex, Kind.FLOW, unsupported = "流式集合")
        }

        return Node(
            path = path,
            key = key,
            indent = indent,
            line = lineIndex,
            kind = Kind.SCALAR,
            valueStart = valueColumn,
            valueEnd = indent + scalarEnd(content, valueColumn - indent),
        )
    }

    /** 最后一个内容行的下一行；中间的注释 / 空行不算块内容。 */
    private fun blockEnd(lines: List<String>, keyLine: Int, indent: Int): Int {
        var last = keyLine
        var i = keyLine + 1
        while (i < lines.size) {
            val raw = lines[i]
            if (isContent(raw)) {
                if (indentOf(raw) <= indent) break
                last = i
            }
            i++
        }
        return last + 1
    }

    private fun nextContent(lines: List<String>, from: Int, until: Int): Int {
        for (i in from until until) if (isContent(lines[i])) return i
        return -1
    }

    private fun isContent(raw: String): Boolean {
        val trimmed = raw.trim()
        return trimmed.isNotEmpty() && !trimmed.startsWith("#")
    }

    private fun indentOf(raw: String): Int = raw.indexOfFirst { it != ' ' }.let { if (it < 0) raw.length else it }

    /** 键与值之间的冒号；跳过引号内的冒号，遇到注释行返回 -1。 */
    private fun keyColon(content: String): Int {
        var quote: Char? = null
        var i = 0
        while (i < content.length) {
            val c = content[i]
            if (quote != null) {
                if (c == '\\' && quote == '"') {
                    i += 2
                    continue
                }
                if (c == quote) quote = null
            } else {
                when (c) {
                    '\'', '"' -> quote = c
                    '#' -> return -1
                    ':' -> if (i + 1 == content.length || content[i + 1] == ' ') return i
                }
            }
            i++
        }
        return -1
    }

    /** 值的结束列（相对 [content]）；引号串含引号本身，普通标量在行尾注释前结束。 */
    private fun scalarEnd(content: String, start: Int): Int {
        if (start >= content.length) return content.length
        val first = content[start]
        if (first == '\'' || first == '"') {
            var i = start + 1
            while (i < content.length) {
                val c = content[i]
                if (first == '"' && c == '\\') {
                    i += 2
                    continue
                }
                if (c == first) {
                    if (first == '\'' && i + 1 < content.length && content[i + 1] == '\'') {
                        i += 2
                        continue
                    }
                    return i + 1
                }
                i++
            }
            return content.length
        }

        var i = start
        while (i < content.length) {
            if (content[i] == '#' && i > start && content[i - 1].isWhitespace()) {
                return content.substring(0, i).trimEnd().length
            }
            i++
        }
        return content.trimEnd().length
    }

    private fun unquote(text: String): String =
        if (text.length >= 2 && (text.first() == '\'' || text.first() == '"') && text.last() == text.first()) {
            text.substring(1, text.length - 1)
        } else {
            text
        }

    // ---- 编辑 ----

    private fun applyObject(
        previous: JsonObject,
        next: JsonObject,
        scope: Scope,
        lines: List<String>,
        edits: MutableList<Edit>,
    ) {
        for ((key, value) in next) {
            val oldValue = previous[key]
            if (oldValue == value) continue
            applyValue(scope.nodes.firstOrNull { it.key == key }, key, oldValue, value, scope, lines, edits)
        }
    }

    private fun applyValue(
        node: Node?,
        key: String,
        previous: JsonElement?,
        value: JsonElement,
        scope: Scope,
        lines: List<String>,
        edits: MutableList<Edit>,
    ) {
        if (previous == value) return

        if (value is JsonObject && node != null && node.kind == Kind.MAP) {
            applyObject(
                previous = previous as? JsonObject ?: JsonObject(emptyMap()),
                next = value,
                scope = childScope(node),
                lines = lines,
                edits = edits,
            )
            return
        }

        if (node == null) {
            edits += Edit(scope.insertAt, scope.insertAt, renderEntry(key, value, scope.indent))
            return
        }

        node.unsupported?.let {
            throw UnsupportedOperationException("配置节点 ${node.path} 不支持写入：$it")
        }

        if (value is JsonPrimitive && node.kind == Kind.SCALAR) {
            val line = lines[node.line]
            val updated = line.substring(0, node.valueStart) + scalarText(value) + line.substring(node.valueEnd)
            edits += Edit(node.line, node.line + 1, listOf(updated))
            return
        }

        edits += Edit(node.line, node.end, renderEntry(key, value, node.indent))
    }

    private fun childScope(node: Node): Scope {
        val indent = node.children.firstOrNull()?.indent ?: (node.indent + 2)
        return Scope(node.children, indent, node.end)
    }

    private fun applyEdits(lines: MutableList<String>, edits: List<Edit>) {
        // 同一起点的多个插入合并，保持键的出现顺序
        val merged = mutableListOf<Edit>()
        val insertions = linkedMapOf<Int, MutableList<String>>()
        edits.forEach { edit ->
            if (edit.start == edit.end) {
                insertions.getOrPut(edit.start) { mutableListOf() }.addAll(edit.lines)
            } else {
                merged += edit
            }
        }
        insertions.forEach { (at, added) -> merged += Edit(at, at, added) }

        var lastStart = Int.MAX_VALUE
        for (edit in merged.sortedWith(compareByDescending<Edit> { it.start }.thenBy { it.end })) {
            if (edit.end > lastStart) {
                throw IllegalStateException("配置写入区间重叠：${edit.start}..${edit.end}")
            }
            for (i in edit.end - 1 downTo edit.start) lines.removeAt(i)
            lines.addAll(edit.start, edit.lines)
            lastStart = edit.start
        }
    }

    // ---- 渲染 ----

    private fun renderWhole(value: JsonElement): String {
        val lines = if (value is JsonArray) renderList(value, 0) else listOf(scalarText(value))
        return lines.joinToString("\n") + "\n"
    }

    private fun renderEntry(key: String, value: JsonElement, indent: Int): List<String> {
        val pad = " ".repeat(indent)
        val name = quoteKey(key)
        return when (value) {
            is JsonObject ->
                if (value.isEmpty()) {
                    listOf("$pad$name: {}")
                } else {
                    listOf("$pad$name:") +
                        value.entries.flatMap { (k, v) -> renderEntry(k, v, indent + 2) }
                }

            is JsonArray ->
                if (value.isEmpty()) {
                    listOf("$pad$name: []")
                } else {
                    listOf("$pad$name:") + renderList(value, indent + 2)
                }

            else -> listOf("$pad$name: ${scalarText(value)}")
        }
    }

    private fun renderList(items: JsonArray, indent: Int): List<String> {
        val pad = " ".repeat(indent)
        return items.flatMap { item ->
            when (item) {
                is JsonObject ->
                    if (item.isEmpty()) {
                        listOf("$pad- {}")
                    } else {
                        listOf("$pad-") + item.entries.flatMap { (k, v) -> renderEntry(k, v, indent + 2) }
                    }

                is JsonArray ->
                    if (item.isEmpty()) {
                        listOf("$pad- []")
                    } else {
                        listOf("$pad-") + renderList(item, indent + 2)
                    }

                else -> listOf("$pad- ${scalarText(item)}")
            }
        }
    }

    private fun scalarText(value: JsonElement): String = when (value) {
        JsonNull -> "null"
        is JsonPrimitive -> if (value.isString) quoteScalar(value.content) else value.content
        else -> throw IllegalArgumentException("不是标量：$value")
    }

    private fun quoteKey(key: String): String = if (PLAIN_KEY.matches(key)) key else quoteScalar(key)

    private fun quoteScalar(value: String): String {
        if (value.isEmpty()) return "\"\""
        val looksTyped = NUMBER.matches(value) ||
            value == "true" || value == "false" || value == "null" || value == "~"
        val plain = !looksTyped &&
            value.first() !in "-?:,[]{}#&*!|>'\"%@`" &&
            value.first() != ' ' && value.last() != ' ' &&
            !value.contains(" #") && !value.contains(": ") && !value.contains('\n')

        if (plain) return value
        return buildString {
            append('"')
            value.forEach { c ->
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
            append('"')
        }
    }

    companion object {

        private val yaml = Yaml.default
        private val PLAIN_KEY = Regex("[A-Za-z0-9_.-]+")
        private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

        fun parse(text: String): YamlDocument {
            if (text.isBlank()) return YamlDocument(text, JsonObject(emptyMap()))
            return YamlDocument(text, yaml.parseToYamlNode(text).toJsonElement())
        }
    }
}

/** YAML 节点 → JSON 树；标量保留原文，由目标 serializer 决定具体类型。 */
private fun YamlNode.toJsonElement(): JsonElement = when (this) {
    is YamlNull -> JsonNull
    is YamlScalar -> JsonPrimitive(content)
    is YamlList -> JsonArray(items.map { it.toJsonElement() })
    is YamlMap -> JsonObject(entries.entries.associate { it.key.content to it.value.toJsonElement() })
    is YamlTaggedNode -> innerNode.toJsonElement()
}
