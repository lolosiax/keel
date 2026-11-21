package moe.lolosia.keel.orm

/**
 * 一次查询的构建作用域。
 *
 * 泛型入口 `db.query<SysUser> { ... }` 的 lambda 接收者就是它；列访问器
 * （`val QueryScope<SysUser>.userName`）由 `keel-orm-ksp` 生成在实体所在包，
 * 查询代码与实体同包时不需要额外 import。
 *
 * 谓词只在 [where] / [and] / [or] 的 lambda 里声明，值全走参数绑定；
 * 不接受字符串列名或排序表达式。
 */
class QueryScope<T : Any> internal constructor(
    val meta: EntityMeta<T>,
    internal val dialect: SqlDialect,
) {

    private val filters = mutableListOf<Expr>()
    private val groups = mutableListOf<MutableList<Expr>>()

    internal val orders = mutableListOf<OrderTerm>()
    internal var limitValue: Int? = null
    internal var offsetValue: Int? = null

    private val current: MutableList<Expr> get() = groups.lastOrNull() ?: filters

    /** 顶层条件，多个条件之间是 AND。 */
    fun where(block: QueryScope<T>.() -> Unit) {
        apply(block)
    }

    /** 显式 AND 分组：`and { ... }`。 */
    fun and(block: QueryScope<T>.() -> Unit) = group("AND", block)

    /** 显式 OR 分组：`or { ... }`。 */
    fun or(block: QueryScope<T>.() -> Unit) = group("OR", block)

    fun orderBy(vararg terms: OrderTerm) {
        orders += terms
    }

    fun limit(value: Int): QueryScope<T> = apply { limitValue = value }

    fun offset(value: Int): QueryScope<T> = apply { offsetValue = value }

    /** 生成物用的列工厂；业务代码通过生成的扩展属性访问列。 */
    fun <V> col(name: String, type: OrmType<V>): Col<V> = Col(name, type)

    infix fun <V> Col<V>.eq(value: V) = add(CompareExpr(this, "=", value))

    infix fun <V> Col<V>.ne(value: V) = add(CompareExpr(this, "<>", value))

    infix fun <V> Col<V>.gt(value: V) = add(CompareExpr(this, ">", value))

    infix fun <V> Col<V>.ge(value: V) = add(CompareExpr(this, ">=", value))

    infix fun <V> Col<V>.lt(value: V) = add(CompareExpr(this, "<", value))

    infix fun <V> Col<V>.le(value: V) = add(CompareExpr(this, "<=", value))

    fun <V> Col<V>.isNull() = add(NullCheckExpr(this, true))

    fun <V> Col<V>.isNotNull() = add(NullCheckExpr(this, false))

    fun <V> Col<V>.isIn(values: Iterable<V>) = add(InExpr(this, values.toList(), false))

    fun <V> Col<V>.isNotIn(values: Iterable<V>) = add(InExpr(this, values.toList(), true))

    infix fun <V : Comparable<V>> Col<V>.between(range: ClosedRange<V>) =
        add(BetweenExpr(this, range.start, range.endInclusive))

    /** 原始 LIKE 模式；用户输入请用 [contains] / [startsWith] / [endsWith]。 */
    fun Col<String>.like(pattern: String) = add(LikeExpr(this, pattern, false))

    fun Col<String>.notLike(pattern: String) = add(LikeExpr(this, pattern, true))

    fun Col<String>.contains(text: String) = add(LikeExpr(this, "%${escapeLike(text)}%", false))

    fun Col<String>.startsWith(text: String) = add(LikeExpr(this, "${escapeLike(text)}%", false))

    fun Col<String>.endsWith(text: String) = add(LikeExpr(this, "%${escapeLike(text)}", false))

    fun <V> Col<V>.asc(): OrderTerm = OrderTerm(this, false)

    fun <V> Col<V>.desc(): OrderTerm = OrderTerm(this, true)

    private fun add(expr: Expr) {
        current += expr
    }

    private fun group(conjunction: String, block: QueryScope<T>.() -> Unit) {
        val bucket = mutableListOf<Expr>()
        groups += bucket
        try {
            apply(block)
        } finally {
            groups.removeAt(groups.lastIndex)
        }
        // 分组以当前组为父节点，所以空 lambda 也会留下恒真条件，保持 SQL 合法
        add(GroupExpr(conjunction, bucket))
    }

    internal fun renderWhere(out: SqlRender): String =
        if (filters.isEmpty()) "" else filters.joinToString(" AND ") { it.render(out) }

    private fun escapeLike(text: String): String = text
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
}
