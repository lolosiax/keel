package moe.lolosia.keel.orm

/** 括号分组：`(a AND b)` / `(a OR b)`；空组渲染成恒真，避免非法 SQL。 */
class GroupExpr(
    private val conjunction: String,
    private val children: List<Expr>,
) : Expr {

    override fun render(out: SqlRender): String {
        if (children.isEmpty()) return "1 = 1"
        return children.joinToString(" $conjunction ", prefix = "(", postfix = ")") { it.render(out) }
    }
}
