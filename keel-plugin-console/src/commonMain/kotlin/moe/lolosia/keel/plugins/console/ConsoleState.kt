package moe.lolosia.keel.plugins.console

/**
 * 插件运行期的跨窗口状态。
 *
 * 标题放在这里而不是 `Plugin` 实例字段：关窗、重开、`onDisable → onEnable` 都保留，
 * 只有进程退出才回到配置默认值；不写磁盘，也不回写配置。
 */
internal object ConsoleState {

    var settings: ConsoleSettings = ConsoleSettings()
        private set

    var title: String = ConsoleSettings().title
        private set

    private var initialized = false

    /** 首次加载才采用配置里的标题；之后只由 `title` 消息修改。 */
    fun load(value: ConsoleSettings) {
        settings = value
        if (!initialized) {
            title = value.title
            initialized = true
        }
    }

    fun setTitle(value: String) {
        title = value
    }
}
