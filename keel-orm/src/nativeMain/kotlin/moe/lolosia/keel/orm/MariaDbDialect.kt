package moe.lolosia.keel.orm

/** MariaDB / MySQL：反引号标识符，`?` 占位符，`last_insert_id()` 回填。 */
object MariaDbDialect : SqlDialect {

    override val id: String = "mariadb"

    override fun quote(name: String): String = "`" + name.replace("`", "``") + "`"

    /** SQL 文本是 `escape '\\'`：MariaDB 会把 `'\'` 当成未闭合字符串。 */
    override val likeEscape: String = "'\\\\'"

    override val lastInsertIdSql: String = "select last_insert_id()"
}
