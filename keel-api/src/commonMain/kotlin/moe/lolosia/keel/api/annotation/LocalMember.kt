package moe.lolosia.keel.api.annotation

/**
 * 标记 `@PublicApi` 契约里的本地成员：不属于跨插件契约面。
 *
 * 契约接口允许携带实现侧或本地生命周期需要的成员（例如
 * [moe.lolosia.keel.api.web.Component.context]）。标上本注解后：
 * - KSP 不把它生成为跨边界方法，不进入分发器的 `when (method)`；
 * - 生成的代理在本地实现它（属性用默认值初始化），不访问句柄。
 *
 * 需要跨插件读取的状态不要导出属性，写成 `suspend fun getX()` 访问器；
 * 本注解只用于确实不需要跨界、但契约类型本身必须携带的本地状态。
 *
 * 生成器约束：标在属性上时必须是**可空 `var`**，代理才能用 `null` 初始化；
 * 标在方法上时必须带默认实现，否则代理无法满足接口。
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class LocalMember
