package moe.lolosia.keel.api.annotation

import kotlin.reflect.KClass

/**
 * 声明 `@PublicApi` 类型在声明方侧的分发器实现。
 *
 * KSP 用它生成 `KeelPluginPublicApi.setup`：把实现实例放进 `handlers`，
 * 并在类型是 `@KeelPluginMain` 时复用同一个分发器登记单例。
 *
 * 实现类的主构造器只允许：无参、单个 [moe.lolosia.keel.api.PluginContext]，
 * 或单个插件本体类型的参数。
 *
 * @property type 该分发器负责的共享类型
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class PublicApiHandler(val type: KClass<*>)
