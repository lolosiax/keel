package moe.lolosia.keel.orm

/** `IN` / `NOT IN`；空集合渲染成恒假 / 恒真，不生成非法的 `IN ()`。 */
class InExpr<T>(
    private val col: Col<T>,
    private val values: List<T>,
    private val negate: Boolean,
) : Expr {

    override fun render(out: SqlRender): String {
        if (values.isEmpty()) return if (negate) "1 = 1" else "1 = 0"
        val placeholders = values.joinToString(", ") { out.bind(col.type.encode(it)) }
        val operator = if (negate) "not in" else "in"
        return "${out.quote(col.name)} $operator ($placeholders)"
    }
}
