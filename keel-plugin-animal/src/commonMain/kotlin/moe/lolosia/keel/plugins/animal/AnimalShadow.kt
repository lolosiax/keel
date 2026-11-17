package moe.lolosia.keel.plugins.animal

/**
 * 声明方为外来子类实例保留的基类影子。
 *
 * 基类体与裸字段都在这里执行；`baseXxx()` 只跑基类体，供镜像桩与子类的 `super.x()` 进入。
 * 覆盖方法是否转回实例方由分发器按 override 集合决定，不写在影子里。
 */
class AnimalShadow : Animal() {

    override fun bark() {
        super.bark()
    }

    fun baseBark() {
        super.bark()
    }

    override fun describe(): String = super.describe()

    fun baseDescribe(): String = super.describe()
}
