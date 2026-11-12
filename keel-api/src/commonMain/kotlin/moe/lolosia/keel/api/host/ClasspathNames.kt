package moe.lolosia.keel.api.host

/** 跨运行时 classpath 请求使用的保留命名空间。 */
object ClasspathNames {

    /** Host 自身的资源视图；其余取值都是插件 `plugin.json` 里的 id。 */
    const val HOST: String = "__host__"
}
