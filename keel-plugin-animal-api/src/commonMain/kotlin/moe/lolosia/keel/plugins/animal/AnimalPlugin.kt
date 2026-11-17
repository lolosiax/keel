package moe.lolosia.keel.plugins.animal

/**
 * 声明方插件的跨插件视图。
 *
 * 只导出标了 `@PublicApi` 的成员；生命周期钩子不参与共享。
 */
interface AnimalPlugin {

    /**
     * 接收一个回调并在声明方运行时里立即调用它。
     *
     * 用来验证「参数按句柄传、方法在声明方执行、覆盖方法再转回实例方」这条链路。
     */
    suspend fun register(callable: Callable)
}
