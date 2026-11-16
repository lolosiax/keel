package moe.lolosia.keel.api.debug

/**
 * 命中瞬间的变量快照 + 句柄表。
 *
 * 命中时把一帧的「名字 / 声明类型 / 预览 / 句柄」发出去；IDE 展开某个句柄时再按需
 * 枚举它的子成员（懒加载，一次一层）。句柄只在下一次命中之前有效：
 * [reset] 在命中与恢复时都清表，IDE 侧按 `stopSeq` 丢弃过期应答。
 *
 * 这是一个调试专用实现：`toString()` 有保护、数量有上限，值本身不做深拷贝。
 * KN 没有运行时反射，所以成员来自 [DebugView]（编译器生成）与少数内置适配器。
 */
internal object DebugValues {

    private const val MAX_HANDLES = 4096
    private const val MAX_CHILDREN = 200
    private const val PREVIEW_LIMIT = 240

    /** 句柄 → 对象；只保存「可展开」的值，0 表示不可展开。 */
    private val handles = HashMap<Int, Any>(64)

    private var nextHandle = 1

    /** 清空句柄表；命中与恢复时各调一次。 */
    fun reset() {
        handles.clear()
        nextHandle = 1
    }

    /**
     * 一帧的值快照，返回 `FRAME_VALUES` 载荷：
     * `locationId \t count` + 每行 `name \t type \t preview \t ref`。
     */
    fun frameValues(
        locationId: Int,
        names: Array<String>,
        types: Array<String>,
        values: Array<Any?>,
    ): String {
        // 上一轮的句柄已经失效；命中与恢复都会清，这里再兜一次底
        reset()
        val builder = StringBuilder(128)
        builder.append(locationId).append('\t').append(values.size)
        values.forEachIndexed { index, value ->
            builder.append('\t').append(escape(names.getOrElse(index) { "" }))
            builder.append('\t').append(escape(types.getOrElse(index) { "" }))
            builder.append('\t').append(escape(safePreview(value)))
            builder.append('\t').append(handleFor(value))
        }
        return builder.toString()
    }

    /**
     * 展开一个句柄，返回 `MEMBERS` 载荷：
     * `ref \t stopSeq \t typeName \t count` + 每行 `name \t type \t preview \t childRef`。
     *
     * 句柄不存在（属于上一轮命中）时返回 null，运行期不回帧。
     */
    fun members(ref: Int, stopSeq: Int): String? {
        val value = handles[ref] ?: return null
        val rows = StringBuilder(256)
        var count = 0
        forEachMember(value) { name, type, child ->
            rows.append('\t').append(escape(name))
            rows.append('\t').append(escape(type))
            rows.append('\t').append(escape(safePreview(child)))
            rows.append('\t').append(handleFor(child))
            count++
        }
        return "$ref\t$stopSeq\t${escape(typeNameOf(value))}\t$count$rows"
    }

    /** 可展开的成员；顺序：内置适配器在前，[DebugView] 的实例成员在后。 */
    private fun forEachMember(value: Any, action: (String, String, Any?) -> Unit) {
        var count = 0
        fun emit(name: String, type: String, child: Any?) {
            if (count >= MAX_CHILDREN) return
            count++
            action(name, type, child)
        }

        if (value is Throwable) {
            emit("message", "String?", value.message)
            emit("cause", "Throwable?", value.cause)
        }
        if (value is Enum<*>) {
            emit("name", "String", value.name)
            emit("ordinal", "Int", value.ordinal)
        }

        when (value) {
            is Map<*, *> -> {
                var index = 0
                for ((key, item) in value) {
                    if (count >= MAX_CHILDREN) break
                    emit("[$index].key", typeNameOfOrNull(key), key)
                    emit("[$index].value", typeNameOfOrNull(item), item)
                    index++
                }
            }

            is Collection<*> -> {
                var index = 0
                for (item in value) {
                    if (count >= MAX_CHILDREN) break
                    emit("[$index]", typeNameOfOrNull(item), item)
                    index++
                }
            }

            is Array<*> -> {
                var index = 0
                while (index < value.size && count < MAX_CHILDREN) {
                    emit("[$index]", typeNameOfOrNull(value[index]), value[index])
                    index++
                }
            }
        }

        if (value is DebugView) {
            val total = value.keelDebugMemberCount()
            var index = 0
            while (index < total && count < MAX_CHILDREN) {
                emit(
                    value.keelDebugMemberName(index),
                    value.keelDebugMemberType(index),
                    value.keelDebugMember(index),
                )
                index++
            }
        }
    }

    /** 只有存在子成员的对象才分配句柄，标量与字符串不占表。 */
    private fun handleFor(value: Any?): Int {
        if (!expandable(value)) return 0
        if (handles.size >= MAX_HANDLES) return 0
        val id = nextHandle++
        handles[id] = value as Any
        return id
    }

    private fun expandable(value: Any?): Boolean {
        if (value == null) return false
        if (value is DebugView) return value.keelDebugMemberCount() > 0
        return value is Throwable ||
            value is Enum<*> ||
            value is Map<*, *> ||
            value is Collection<*> ||
            value is Array<*>
    }

    private fun typeNameOfOrNull(value: Any?): String = if (value == null) "null" else typeNameOf(value)

    private fun typeNameOf(value: Any): String = when (value) {
        is DebugView -> value.keelDebugTypeName()
        is String -> "String"
        is Char -> "Char"
        is Boolean -> "Boolean"
        is Byte -> "Byte"
        is Short -> "Short"
        is Int -> "Int"
        is Long -> "Long"
        is Float -> "Float"
        is Double -> "Double"
        is Unit -> "Unit"
        is Enum<*> -> "Enum"
        is Throwable -> "Throwable"
        is Map<*, *> -> "Map"
        is Set<*> -> "Set"
        is List<*> -> "List"
        is Collection<*> -> "Collection"
        is Array<*> -> "Array"
        else -> "Any"
    }

    /** `toString()` 是用户代码，可能抛异常；一个坏成员不该让整次展开失败。 */
    private fun safePreview(value: Any?): String =
        runCatching { preview(value) }.getOrElse { "<error>" }

    private fun preview(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Char -> "'$value'"
        is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> value.toString()
        is Unit -> "Unit"
        is Enum<*> -> value.name
        is Map<*, *> -> "Map(size=${value.size})"
        is Set<*> -> "Set(size=${value.size})"
        is List<*> -> "List(size=${value.size})"
        is Collection<*> -> "Collection(size=${value.size})"
        is Array<*> -> "Array(size=${value.size})"
        is DebugView -> capped(value.toString())
        is Throwable -> capped(value.toString())
        else -> capped(runCatching { value.toString() }.getOrElse { "?" })
    }

    private fun quote(text: String): String =
        "\"" + (if (text.length > PREVIEW_LIMIT) text.take(PREVIEW_LIMIT) + "…" else text) + "\""

    private fun capped(text: String): String =
        if (text.length > PREVIEW_LIMIT) text.take(PREVIEW_LIMIT) + "…" else text

    /** 值里可能有制表符 / 换行，帧载荷按 `\t` 分隔，先转义。 */
    private fun escape(text: String): String {
        var needs = false
        for (char in text) {
            if (char == '\\' || char == '\t' || char == '\n' || char == '\r') {
                needs = true
                break
            }
        }
        if (!needs) return text
        return buildString(text.length + 8) {
            text.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '\t' -> append("\\t")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(char)
                }
            }
        }
    }
}
