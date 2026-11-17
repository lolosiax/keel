package moe.lolosia.keel.plugins.animal

import moe.lolosia.keel.api.annotation.PublicApi
import moe.lolosia.keel.api.annotation.Singleton

/**
 * 声明方插件的跨插件视图。
 *
 * 插件本体是公开单例，所以镜像上同时标 `@Singleton`，由 KSP 生成 `ctx.animalPlugin`。
 */
@PublicApi
@Singleton
interface AnimalPlugin {

    /**
     * 接收一个回调并在声明方运行时里立即调用它。
     *
     * 用来验证「参数按句柄传、方法在声明方执行、覆盖方法再转回实例方」这条链路。
     */
    suspend fun register(callable: Callable)
}
