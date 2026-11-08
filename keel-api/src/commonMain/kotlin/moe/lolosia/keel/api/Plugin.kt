package moe.lolosia.keel.api

import moe.lolosia.keel.api.auth.*
import moe.lolosia.keel.api.command.*

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

    /**
     * 认证 SPI 实现；只有 `plugin.json` 声明 `provides: ["authn"]` 的插件才会被 Host 调用。
     *
     * Host 在 `onLoad` 之后取一次并缓存，返回 null 表示本插件不提供认证。
     */
    fun authenticator(): Authenticator? = null

    /**
     * 授权 SPI 实现；只有 `plugin.json` 声明 `provides: ["authz"]` 的插件才会被 Host 调用。
     *
     * 与 [authenticator] 相同，Host 在 `onLoad` 之后取一次。
     */
    fun authorizer(): Authorizer? = null

    /**
     * 控制台命令回调，语义照搬 Bukkit `Plugin.onCommand`。
     *
     * Host 先匹配自己的运维命令；没有命中时按启用顺序逐个询问插件，
     * 第一个返回 true 的插件结束分发，因此**不认识的 label 必须返回 false**，
     * 否则会挡住后面的插件。
     *
     * 输出通过 [sender] 写入，Host 原样打印；返回 true 表示命令已处理，
     * 返回 false 时 [sender] 已写入的内容仍会打印，但没有成功语义。
     *
     * @param command 命令元信息；当前只有 label 有值（`plugin.json` 的命令声明尚未解析）
     * @param label   实际输入的 label（已转小写）
     * @param args    按空格拆分后的参数，不含 label
     */
    fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: List<String>,
    ): Boolean = false
}
