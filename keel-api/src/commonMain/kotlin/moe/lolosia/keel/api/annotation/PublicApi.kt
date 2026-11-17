package moe.lolosia.keel.api.annotation

/**
 * 标记需要跨插件共享的类型或成员。
 *
 * 标在类型上：该类型会出现在声明插件的 `-api` 工件里，供其它插件在编译期引用；
 * 标在成员上：类模式下只有被标注的成员参与共享，其余成员不导出。
 *
 * 类与成员共用一个注解：Kotlin 不允许同包内存在两个同名注解，
 * 只能靠 `@Target` 区分。
 *
 * @property id 可选的稳定标识；留空时由类型全名推导
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
@Retention(AnnotationRetention.BINARY)
annotation class PublicApi(val id: String = "")
