package moe.lolosia.keel.api.annotation

/**
 * 标记一个 [ModuleService] 接口的实现类。
 *
 * KSP 会为它生成注册代码：模块启用时构造实例并注册到本地服务表，
 * 跨模块调用再经事件泵转发过来。实现类需要有 `(PluginContext)` 或 `()` 构造器。
 *
 * @property serviceId 一个类实现多个服务接口时用它指定；默认按实现的唯一
 *   `@ModuleService` 接口自动推断
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class ModuleServiceProvider(val serviceId: String = "")
