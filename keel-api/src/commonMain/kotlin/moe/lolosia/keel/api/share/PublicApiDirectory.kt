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
     * 注册一个远程构造器。
     *
     * 别的插件 `createInstance` 时，Host 会把请求转发到这里：[factory] 用分配的句柄构造实例，
     * [dispatcher] 提供所有者侧分发。
     *
     * @param interfaces 实例额外导出的类型
     * @param overrides 实例实际覆盖的方法线名
     * @param initialization 类型是否声明了 `initialization`；为 true 时构造完处于 Creating，
     *   只有创建者能初始化，成功后其它方法才可用
     */
    fun <T : Any> constructor(
        key: PublicApiKey<T>,
        factory: (Long) -> T,
        dispatcher: (T) -> PublicApiDispatcher,
        interfaces: List<String> = emptyList(),
        overrides: Set<String> = emptySet(),
        initialization: Boolean = false,
    )

    /**
     * 按类型请求提供者构造一个实例，返回本地代理。
     *
     * 构造经 Host 转发，创建者拿到的是指向提供者实例的句柄代理。
     *
     * @param factory 由生成的代理提供：句柄 → 本地代理
     */
    fun <T : Any> createInstance(key: PublicApiKey<T>, factory: (Long) -> T): T

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

    /** 本地对象或代理的句柄；两者都不是时抛异常。 */
    fun handleOf(instance: Any): Long

    /**
     * 释放本插件登记的实例。
     *
     * 只有登记方可以释放；未登记或不属于本插件时是空操作。
     * 释放后 Host 侧句柄立即失效，其它持有者的后续调用得到
     * “shared object closed” 错误。
     */
    fun release(instance: Any)

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
