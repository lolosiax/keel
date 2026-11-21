package moe.lolosia.keel.orm.annotation

/**
 * 覆盖物理列名或补充 DDL 元数据。
 *
 * @param name 物理列名；留空表示按属性名 camelCase → snake_case 推导
 * @param nullable 只用于元数据 / 建表导出，行映射以属性类型是否可空为准
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Column(val name: String = "", val nullable: Boolean = false)
