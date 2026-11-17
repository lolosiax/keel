package moe.lolosia.keel.api.annotation

/**
 * 标记插件的生命周期入口。
 *
 * 语义上等价于 [PublicApi] + [Singleton]：插件本体是公开单例，
 * 只有标了 [PublicApi] 的成员进入 api 工件。
 *
 * KSP 会生成固定的 Worker 入口 `keel_plugin_worker_main`：
 * 读取 `plugin.json` → 构造该类 → 调用生命周期 → 进入事件循环。
 * 开发者不需要手写注册代码。
 *
 * 被标注的类必须实现 `Plugin` 并有可用的无参构造器。
 */
@PublicApi
@Singleton
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class KeelPluginMain
