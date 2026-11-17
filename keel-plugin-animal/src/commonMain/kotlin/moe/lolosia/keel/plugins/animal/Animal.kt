package moe.lolosia.keel.plugins.animal

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.annotation.PublicApi

/**
 * 声明方插件里真正的 `Animal`：基类状态与基类体都在这里。
 *
 * `-api` 工件里的同名类型是它的镜像（无状态、方法体为桩），两者只靠 typeId 对齐。
 */
@PublicApi
open class Animal {

    /** 基类状态；住在声明方为每个外来实例保留的影子里。 */
    var noise: String = "呼噜"

    /** 基类持有另一个共享对象；只在声明方本地使用，不跨边界导出属性。 */
    var companion: Callable? = null

    open fun bark() {
        Log.info("Animal.bark: $noise")
    }

    open fun describe(): String = "动物[$noise]"
}
