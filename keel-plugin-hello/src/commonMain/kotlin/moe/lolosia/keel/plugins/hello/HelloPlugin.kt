package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.api.Plugin
import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.annotation.KeelPluginMain

/**
 * 最小示例插件：演示生命周期与上下文注入。
 *
 * 元信息来自 `plugin.json`（经 [PluginContext.metadata] 注入），
 * 服务注册由 KSP 生成的 Worker 入口完成，这里只保留生命周期钩子。
 */
@KeelPluginMain
class HelloPlugin : Plugin {

    private var context: PluginContext? = null

    override fun onLoad(context: PluginContext) {
        this.context = context
        context.logger.info("${context.metadata.id} loaded")
    }

    override fun onEnable() {
        context?.let { it.logger.info("${it.metadata.id} enabled") }
    }

    override fun onDisable() {
        context?.let { it.logger.info("${it.metadata.id} disabled") }
        context = null
    }
}
