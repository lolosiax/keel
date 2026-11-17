package moe.lolosia.keel.api.share

/**
 * 插件视角的 `@PublicApi` 目录。
 *
 * 所有跨插件对象都经 Host 路由：本地登记拿到句柄，远端对象拿到句柄后包成本地代理。
 * 这些方法只能在插件 Worker 线程调用。
 */
interface PublicApiDirectory {

    /**
     * 声明本插件提供的类型。
     *
     * @param types 类型描述；Host 用它决定调用先路由到声明方还是直接给所有者
     * @param handlers 声明方侧的分发器，按 [PublicApiDispatcher.typeId] 登记
     */
    fun declare(types: List<PublicApiType>, handlers: List<PublicApiDispatcher> = emptyList())

    /**
     * 登记一个本地对象并分配句柄。
     *
     * 同一个实例重复登记返回同一个句柄。
     *
     * @param interfaces 除 [key] 之外额外导出的类型
     * @param overrides 该实例实际覆盖的方法线名
     * @param singleton 是否作为该类型的单例，供 [requireInstance] 查找
     */
    fun <T : Any> register(
        instance: T,
        key: PublicApiKey<T>,
        dispatcher: PublicApiDispatcher,
        interfaces: List<String> = emptyList(),
        overrides: Set<String> = emptySet(),
        singleton: Boolean = false,
    ): SharedRef

    /** 本地对象的句柄；未登记时抛异常。 */
    fun handleOf(instance: Any): Long

    /**
     * 取声明方登记的单例；不存在时返回 null。
     *
     * @param factory 由生成的代理提供：句柄 → 本地代理
     */
    fun <T : Any> getInstance(key: PublicApiKey<T>, factory: (Long) -> T): T?

    /**
     * 取声明方登记的单例；不存在时抛异常。
     */
    fun <T : Any> requireInstance(key: PublicApiKey<T>, factory: (Long) -> T): T
}
