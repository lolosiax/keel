package moe.lolosia.keel.orm.annotation

/**
 * 主键列；每个实体必须且只能有一个。
 *
 * 常与 [Generated] 搭配表示自增 / 数据库生成的列。
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Id
