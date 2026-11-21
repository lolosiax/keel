package moe.lolosia.keel.orm

/** 单列比较：`= <> > >= < <=`。 */
class CompareExpr<T>(
    private val col: Col<T>,
    private val operator: String,
    private val value: T,
) : Expr {

    override fun render(out: SqlRender): String =
        "${out.quote(col.name)} $operator ${out.bind(col.type.encode(value))}"
}
