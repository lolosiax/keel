package moe.lolosia.keel.gradle.common

import org.gradle.api.Project

/**
 * 所有 keel 工具链插件共用的根容器。
 *
 * 容器本身不知道任何 section 类型：每个插件 apply 时把自己的 section 注册进来，
 * 并在同包（`moe.lolosia.keel.gradle.common`）提供 DSL 扩展函数，例如：
 *
 * ```kotlin
 * import moe.lolosia.keel.gradle.common.*
 *
 * keel {
 *     plugin { target("mingwX64") }
 *     thirdparty { library("sqlite") }
 * }
 * ```
 *
 * 只有 apply 了对应插件，build script 的 classpath 里才存在该 section 的扩展函数；
 * 因此不会出现「没装的插件留下空壳 section」。
 */
open class KeelExtension {

    private val sections = mutableMapOf<Class<*>, Any>()

    /** 注册一个 section；同一类型重复注册时以最后一次为准。 */
    fun <T : Any> register(section: T): T {
        sections[section.javaClass] = section
        return section
    }

    /** 取 section，缺失时返回 null。 */
    fun <T : Any> find(type: Class<T>): T? = type.cast(sections[type])

    /** 取 section，缺失时给出可操作的报错。 */
    fun <T : Any> get(type: Class<T>): T =
        find(type) ?: error(
            "keel: ${type.simpleName} 未注册；请确认已应用对应的 moe.lolosia.keel.* 插件"
        )

    companion object {

        /** 扩展名；各插件都通过 [of] 复用，不要另起名字。 */
        const val NAME = "keel"

        /** 取当前工程的根容器，没有时创建。 */
        fun of(project: Project): KeelExtension =
            project.extensions.findByType(KeelExtension::class.java)
                ?: project.extensions.create(NAME, KeelExtension::class.java)
    }
}
