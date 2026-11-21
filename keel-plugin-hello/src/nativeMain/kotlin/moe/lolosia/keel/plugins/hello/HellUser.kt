package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.orm.Decimal
import moe.lolosia.keel.orm.annotation.Generated
import moe.lolosia.keel.orm.annotation.Id
import moe.lolosia.keel.orm.annotation.Table
import kotlin.time.Instant

/**
 * ORM 垂直切片的测试实体。
 *
 * 覆盖：数据库生成主键、非空 / 可空文本、布尔、精确十进制、时间。
 * 列名由属性名推导（`user_name` / `is_use` / `created_at`），与建表 SQL 对齐。
 */
@Table("hell_user")
class HellUser {

    @Id
    @Generated
    var id: Long = 0

    var userName: String = ""

    var email: String? = null

    var isUse: Boolean = true

    var score: Decimal? = null

    var createdAt: Instant? = null
}
