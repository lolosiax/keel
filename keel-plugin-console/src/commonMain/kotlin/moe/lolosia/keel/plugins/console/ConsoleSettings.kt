package moe.lolosia.keel.plugins.console

import kotlinx.serialization.Serializable

/**
 * 插件私有配置（`config/plugins/console.yaml`）。
 *
 * @property autoOpen 启用时是否自动开窗
 * @property startId 日志起点：`0` 全量回放，`-1` 只收打开后的新日志，`>0` 从该 id 之后开始
 * @property maxReplayLines 首次回放上限；`0` 表示不限
 * @property scrollback 控制台缓冲行数
 * @property title 初始标题；运行期被消息改过不写回这里
 * @property input 输入模式：`line` 整行转发给 Host；`off` 不读输入
 */
@Serializable
data class ConsoleSettings(
    val autoOpen: Boolean = false,
    val startId: Long = 0,
    val maxReplayLines: Int = 0,
    val scrollback: Int = 10_000,
    val title: String = "Keel Console",
    val input: String = "line",
)
