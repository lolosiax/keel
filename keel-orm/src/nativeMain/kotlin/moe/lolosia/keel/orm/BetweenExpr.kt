package moe.lolosia.keel.orm

/** `BETWEEN ? AND ?`，闭区间。 */
class BetweenExpr<T>(
    private val col: Col<T>,
    private val from: T,
    private val to: T,
) : Expr {

    override fun render(out: SqlRender): String {
        val low = out.bind(col.type.encode(from))
        val high = out.bind(col.type.encode(to))
        return "${out.quote(col.name)} between $low and $high"
    }
}
