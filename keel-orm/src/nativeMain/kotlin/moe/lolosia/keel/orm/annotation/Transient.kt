package moe.lolosia.keel.orm.annotation

/**
 * 不是数据库列：不进 meta、不参与读写与序列化。
 *
 * 与 `kotlinx.serialization.Transient` 无关；ORM 只认这个注解。
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Transient
