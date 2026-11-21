// 由 keel-orm-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.hello

import kotlin.reflect.KClass
import moe.lolosia.keel.database.DbValue
import moe.lolosia.keel.database.Row
import moe.lolosia.keel.orm.*

object HellUserMeta : EntityMeta<HellUser> {
    override val type: KClass<HellUser> = HellUser::class
    override val table: String = "hell_user"
    override val columns: List<String> = listOf("id", "user_name", "email", "is_use", "score", "created_at")
    override val insertColumns: List<String> = listOf("user_name", "email", "is_use", "score", "created_at")
    override val updateColumns: List<String> = listOf("user_name", "email", "is_use", "score", "created_at")
    override val idColumn: String = "id"
    override val generatedId: Boolean = true

    override fun new(): HellUser = HellUser()

    override fun read(row: Row): HellUser = HellUser().apply {
        id = OrmTypes.INT.decode(row, 0)
        userName = OrmTypes.TEXT.decode(row, 1)
        email = OrmTypes.TEXT.decodeOrNull(row, 2)
        isUse = OrmTypes.BOOL.decode(row, 3)
        score = OrmTypes.DECIMAL.decodeOrNull(row, 4)
        createdAt = OrmTypes.INSTANT.decodeOrNull(row, 5)
    }

    override fun writeInsert(value: HellUser): List<DbValue> = listOf(
        OrmTypes.TEXT.encode(value.userName),
        value.email?.let { OrmTypes.TEXT.encode(it) } ?: DbValue.Null,
        OrmTypes.BOOL.encode(value.isUse),
        value.score?.let { OrmTypes.DECIMAL.encode(it) } ?: DbValue.Null,
        value.createdAt?.let { OrmTypes.INSTANT.encode(it) } ?: DbValue.Null
    )

    override fun writeUpdate(value: HellUser): List<DbValue> = listOf(
        OrmTypes.TEXT.encode(value.userName),
        value.email?.let { OrmTypes.TEXT.encode(it) } ?: DbValue.Null,
        OrmTypes.BOOL.encode(value.isUse),
        value.score?.let { OrmTypes.DECIMAL.encode(it) } ?: DbValue.Null,
        value.createdAt?.let { OrmTypes.INSTANT.encode(it) } ?: DbValue.Null
    )

    override fun idOf(value: HellUser): DbValue =
        OrmTypes.INT.encode(value.id)
}
