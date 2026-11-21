package moe.lolosia.keel.orm

/** 查询条件表达式；渲染时可以向 [SqlRender] 追加参数。 */
interface Expr {

    fun render(out: SqlRender): String
}
