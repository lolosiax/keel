package moe.lolosia.keel.idea.run

import com.intellij.execution.configurations.LocatableRunConfigurationOptions

/**
 * Keel 运行配置的持久化字段。
 *
 * [workDir] 为空时按插件工程的 `build/keel-run` 推导；[port] 与
 * `keelPlugin.runPort` 保持同一默认值。
 */
class KeelRunConfigurationOptions : LocatableRunConfigurationOptions() {
    var pluginProjectDir: String? by string(null)
    var workDir: String? by string(null)
    var port: Int by property(7191)
    var buildBeforeRun: Boolean by property(true)
    var programArguments: String? by string("")
}
