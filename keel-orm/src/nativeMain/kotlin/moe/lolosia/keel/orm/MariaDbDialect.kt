package moe.lolosia.keel.orm

/** MariaDB / MySQL：反引号标识符，`?` 占位符，`last_insert_id()` 回填。 */
object MariaDbDialect : SqlDialect {

    override val id: String = "mariadb"

    override fun quote(name: String): String = "`" + name.replace("`", "``") + "`"

    override val lastInsertIdSql: String = "select last_insert_id()"
}
