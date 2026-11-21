// 由 keel-orm-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.orm.*

val QueryScope<HellUser>.id: Col<Long>
    get() = col("id", OrmTypes.INT)

val QueryScope<HellUser>.userName: Col<String>
    get() = col("user_name", OrmTypes.TEXT)

val QueryScope<HellUser>.email: Col<String>
    get() = col("email", OrmTypes.TEXT)

val QueryScope<HellUser>.isUse: Col<Boolean>
    get() = col("is_use", OrmTypes.BOOL)

val QueryScope<HellUser>.score: Col<moe.lolosia.keel.orm.Decimal>
    get() = col("score", OrmTypes.DECIMAL)

val QueryScope<HellUser>.createdAt: Col<kotlin.time.Instant>
    get() = col("created_at", OrmTypes.INSTANT)
