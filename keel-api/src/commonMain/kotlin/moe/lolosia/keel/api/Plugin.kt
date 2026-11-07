package moe.lolosia.keel.api

/**
 * 一个部署单元的生命周期入口。
 *
 * 宿主按 `onLoad → onEnable → onDisable` 推进；`onLoad` 阶段只做依赖解析与注册，
 * 不应启动线程或占用资源。重复 `onEnable` / `onDisable` 必须幂等。
 *
 * 元信息由宿主从 `plugin.json` 读取后放进 [PluginContext.metadata]，
 * 插件类不需要自己重复声明。服务注册由 KSP 生成的 Worker 入口完成，
 * 开发者只需要实现（可选的）生命周期钩子。
 */
interface Plugin {

    fun onLoad(context: PluginContext) {}

    fun onEnable() {}

    fun onDisable() {}
}
