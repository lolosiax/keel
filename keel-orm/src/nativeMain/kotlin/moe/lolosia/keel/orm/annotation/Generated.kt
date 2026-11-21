package moe.lolosia.keel.orm.annotation

/**
 * 列由数据库生成（自增 / 默认值）。
 *
 * 只对 [Id] 有效：`insert` 跳过该列，写入后可用 `KeelDatabase.lastInsertId()` 回填。
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Generated
