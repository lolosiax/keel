package moe.lolosia.keel.orm.annotation

/**
 * 把一个类声明为数据库实体。
 *
 * 表名显式给出，不做推导；列名默认由属性名 camelCase → snake_case，
 * 只有物理列名确实不同时才写 [Column]。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Table(val value: String)
