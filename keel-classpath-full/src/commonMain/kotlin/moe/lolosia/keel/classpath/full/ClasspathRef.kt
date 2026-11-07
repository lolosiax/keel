package moe.lolosia.keel.classpath.full

import moe.lolosia.keel.classpath.Classpath

/**
 * 绑定某个插件命名空间的 Classpath 引用。
 *
 * - 不暴露 `.keel` 的真实路径；
 * - 每次读取都会校验 generation，插件卸载后立即失效；
 * - `origin` 只返回 `keel://<pluginId>`。
 */
interface ClasspathRef : Classpath {

    val pluginId: String

    /** 注册时的代次；卸载或替换后旧引用失效。 */
    val generation: Long
}
