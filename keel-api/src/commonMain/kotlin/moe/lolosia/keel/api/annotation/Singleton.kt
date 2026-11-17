package moe.lolosia.keel.api.annotation

/**
 * 声明一个单例提供者，或声明某个契约存在单例提供者。
 *
 * - 标在 impl 的实现类上：把它登记为它实现的所有 `@PublicApi` 类型的单例；
 * - 标在 api 镜像上：表示该契约有单例，生成 `ctx.xxx` 访问器；
 * - `@KeelPluginMain` 已隐含本注解，插件本体天然是公开单例。
 *
 * 一个 typeId 只允许一个单例提供者；重复时 Host 拒绝后到者并告警。
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Singleton
