package moe.lolosia.keel.orm

/**
 * `LIKE` / `NOT LIKE`，模式串走参数绑定。
 *
 * SQLite 与 MariaDB 都支持 `ESCAPE '\'`，`contains` / `startsWith` / `endsWith`
 * 会把模式里的 `%` / `_` / `\` 转义，用户输入不会被当成通配符。
 */
class LikeExpr(
    private val col: Col<String>,
    private val pattern: String,
    private val negate: Boolean,
) : Expr {

    override fun render(out: SqlRender): String {
        val operator = if (negate) "not like" else "like"
        return "${out.quote(col.name)} $operator ${out.bind(OrmTypes.TEXT.encode(pattern))} escape ${out.dialect.likeEscape}"
    }
}
