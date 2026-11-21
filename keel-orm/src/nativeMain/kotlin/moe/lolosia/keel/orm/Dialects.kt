package moe.lolosia.keel.orm

/** 方言语义查表；未知 id 返回 null，由调用方决定是否拒绝启用。 */
object Dialects {

    val all: List<SqlDialect> = listOf(SqliteDialect, MariaDbDialect)

    fun of(id: String): SqlDialect? = all.firstOrNull { it.id == id.trim().lowercase() }
}
