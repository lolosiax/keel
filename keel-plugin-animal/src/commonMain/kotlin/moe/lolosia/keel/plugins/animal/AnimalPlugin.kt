package moe.lolosia.keel.plugins.animal

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.Plugin
import moe.lolosia.keel.api.annotation.KeelPluginMain
import moe.lolosia.keel.api.annotation.PublicApi

/**
 * 声明方示例插件：导出 `Animal` / `Callable`，并把自己登记成共享单例。
 *
 * 类型声明、单例登记与分发器收集由 KSP 生成的 `KeelPluginPublicApi` 完成；
 * 本类只保留业务方法。另一个插件通过 `ctx.animalPlugin` 拿到本类的代理。
 */
@PublicApi(ANIMAL_PLUGIN_TYPE_ID)
@KeelPluginMain
class AnimalPlugin : Plugin {

    /**
     * 接收回调并立即调用：`invoke` 在实例方执行，`bark` 在声明方与实例方各执行一半。
     */
    @PublicApi
    suspend fun register(callable: Callable) {
        Log.info("register: 收到 callable")
        val animal = callable.invoke()
        Log.info("register: invoke 返回 Animal，准备 bark")
        animal.bark()
        Log.info("register: describe=${animal.describe()}")
    }

    override fun onEnable() {
        Log.info("enabled")
    }

    override fun onDisable() {
        Log.info("disabled")
    }
}
