package moe.lolosia.keel.api.annotation

import kotlin.reflect.KClass

/**
 * 声明 `@PublicApi` 契约的提供者实现。
 *
 * CONTRACT_FIRST 构建里 impl 不重复标 [PublicApi]；KSP 从本注解拿到契约，
 * 按契约的方法列表生成提供者侧分发器与注册入口。实现类是否满足契约由 Kotlin 编译器保证。
 *
 * 可与 [Singleton] 组合；一个实现可以提供多个契约（如 Context 与 WebContext）。
 *
 * @property contracts 该实现提供的共享契约
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class PublicApiProvider(vararg val contracts: KClass<*>)
