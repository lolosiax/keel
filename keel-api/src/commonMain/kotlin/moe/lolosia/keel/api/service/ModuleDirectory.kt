package moe.lolosia.keel.api.service

/**
 * 跨模块入口。
 *
 * 模块是动态的，不存在 `ctx.modules.moduleB` 这样的编译期字段；按 [ServiceKey] 查找，
 * 拿到的永远是**本地代理**，不是对方的 Kotlin 实例。
 */
interface ModuleDirectory {

    fun <T : Any> get(key: ServiceKey<T>): T?

    /**
     * @throws ServiceUnavailableException 目标模块未启用 / 已崩溃 / 未注册该服务
     */
    fun <T : Any> require(key: ServiceKey<T>): T

    /**
     * 取一个绑定到目标模块的调用器；只给生成的客户端使用。
     */
    fun <T : Any> caller(key: ServiceKey<T>): ServiceCaller
}
