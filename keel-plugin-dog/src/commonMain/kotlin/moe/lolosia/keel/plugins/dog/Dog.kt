package moe.lolosia.keel.plugins.dog

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.plugins.animal.Animal

/**
 * 实例方插件声明的子类。
 *
 * `super.bark()` 走 `-api` 工件里的镜像桩，再回到声明方的影子执行基类体。
 */
class Dog : Animal() {

    override fun bark() {
        Log.info("Dog.bark: 汪汪")
        super.bark()
    }
}
