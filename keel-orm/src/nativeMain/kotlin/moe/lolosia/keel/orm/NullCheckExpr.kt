package moe.lolosia.keel.orm

/** `IS NULL` / `IS NOT NULL`。 */
class NullCheckExpr(private val col: Col<*>, private val isNull: Boolean) : Expr {

    override fun render(out: SqlRender): String =
        "${out.quote(col.name)} " + if (isNull) "is null" else "is not null"
}
