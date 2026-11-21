package moe.lolosia.keel.orm

/**
 * 数据库方言：ORM 只负责渲染，驱动只接收最终 SQL。
 *
 * 标识符一律在渲染期加引号，所以 `order` / `index` 这类关键字不需要改列名。
 */
interface SqlDialect {

    /** 与 `plugin.json` / `keel { database { dialect } }` 一致的 id。 */
    val id: String

    /** 标识符引号；内部同字符双写转义。 */
    fun quote(name: String): String

    /** 第 [index] 个（从 1 开始）占位符。 */
    fun placeholder(index: Int): String = "?"

    /**
     * `LIKE ... ESCAPE` 的子句字面量（含引号）。
     *
     * 默认 `'\'`；MariaDB 的字符串转义要求把反斜杠写成 `'\\'`。
     */
    val likeEscape: String get() = "'\\'"

    /** 自增主键回填 SQL；null 表示该方言没有通用写法。 */
    val lastInsertIdSql: String?
}
