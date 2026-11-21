package moe.lolosia.keel.orm

/** SQLite：双引号标识符，`?` 占位符，`last_insert_rowid()` 回填。 */
object SqliteDialect : SqlDialect {

    override val id: String = "sqlite"

    override fun quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    override val lastInsertIdSql: String = "select last_insert_rowid()"
}
