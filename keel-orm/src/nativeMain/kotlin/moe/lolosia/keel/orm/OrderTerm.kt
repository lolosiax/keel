package moe.lolosia.keel.orm

/** 排序项；只接受生成的列，不提供 `orderBy(String)`。 */
class OrderTerm(private val col: Col<*>, private val descending: Boolean) {

    fun render(out: SqlRender): String =
        out.quote(col.name) + if (descending) " desc" else " asc"
}
