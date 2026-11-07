package moe.lolosia.keel.api.event

/**
 * 本地监听器的执行顺序，与 Bukkit 一致：越靠前越先执行，`MONITOR` 最后且不应修改事件。
 *
 * 跨模块事件不保证优先级：它们在接收方 Worker 上按到达顺序处理。
 */
enum class EventPriority {
    LOWEST,
    LOW,
    NORMAL,
    HIGH,
    HIGHEST,
    MONITOR,
}
