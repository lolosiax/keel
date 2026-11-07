package moe.lolosia.keel.api.service

/**
 * 服务注册表（Bukkit `ServicesManager` 的对应物）。
 *
 * 只负责本运行时的注册 / 查找；跨模块调用经 [ModuleDirectory.caller]，
 * 不在注册表上直接暴露。
 */
interface ServiceRegistry {

    fun <T : Any> register(key: ServiceKey<T>, service: T): Boolean

    fun <T : Any> get(key: ServiceKey<T>): T?

    fun <T : Any> unregister(key: ServiceKey<T>): T?
}
