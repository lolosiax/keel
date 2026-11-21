package moe.lolosia.keel.orm

import moe.lolosia.keel.database.DbValue
import moe.lolosia.keel.database.Row
import kotlin.reflect.KClass

/**
 * 一个实体的运行时元数据，由 `keel-orm-ksp` 为每个 `@Table` 类生成。
 *
 * 列顺序是结果集按位置映射的唯一依据：`columns` 与 `read(row)` 的下标一一对应，
 * 生成的 SQL 永远显式列清单，不使用 `SELECT *`。
 */
interface EntityMeta<T : Any> {

    val type: KClass<T>

    /** 物理表名。 */
    val table: String

    /** 全部列（物理名，未加引号），顺序与 [read] 的下标一致。 */
    val columns: List<String>

    /** `insert` 写入的列；数据库生成的列不在其中。 */
    val insertColumns: List<String>

    /** `update` 写入的列；主键不在其中。 */
    val updateColumns: List<String>

    /** 主键列物理名。 */
    val idColumn: String

    /** 主键是否由数据库生成。 */
    val generatedId: Boolean

    /** 无参构造：实体约定要求字段都有默认值、主键用 `lateinit`。 */
    fun new(): T

    /** 按 [columns] 的位置从结果行读出一个实体。 */
    fun read(row: Row): T

    /** 与 [insertColumns] 对齐的写参数。 */
    fun writeInsert(value: T): List<DbValue>

    /** 与 [updateColumns] 对齐的写参数。 */
    fun writeUpdate(value: T): List<DbValue>

    /** 主键值，用于 `update` / `delete` 的 WHERE。 */
    fun idOf(value: T): DbValue
}
