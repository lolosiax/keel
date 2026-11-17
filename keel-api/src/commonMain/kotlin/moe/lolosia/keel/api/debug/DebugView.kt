package moe.lolosia.keel.api.debug

/**
 * 调试器的只读对象视图。
 *
 * Kotlin/Native 没有可用的运行时反射，类成员名 / 类型 / 取值都拿不到。
 * 编译器插件在**调试构建**里给每个普通类补一份实现（成员顺序与声明顺序一致），
 * 运行期 `Debug` 通过这五个方法把对象摊平成「名字 + 声明类型 + 值」交给 IDE。
 *
 * 这不是公共 API：方法名带 `keelDebug` 前缀，且只在 `keelLogger { debugValues = true }`
 * 的构建里生成。未生成的类仍可被展开，但只有适配器认识的那部分（集合、Map、数组、
 * `Throwable`、枚举）。
 */
interface DebugView {

    /** 运行期类型名，用于变量栏的类型列。 */
    fun keelDebugTypeName(): String

    /** 可展开的实例成员个数。 */
    fun keelDebugMemberCount(): Int

    fun keelDebugMemberName(index: Int): String

    /** 成员的**声明类型**（源码里写的类型），不是运行期类型。 */
    fun keelDebugMemberType(index: Int): String

    fun keelDebugMember(index: Int): Any?
}
