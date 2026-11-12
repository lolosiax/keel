package moe.lolosia.keel.plugins.console

/**
 * 后台 I/O 线程投给 Worker 的事件。
 *
 * 窗口状态只在 Worker 上迁移，所以这些事件都是不可变的快照。
 */
internal sealed class BridgeEvent {

    class Hello(val cols: Int, val rows: Int) : BridgeEvent()

    class Input(val line: String) : BridgeEvent()

    class Resize(val cols: Int, val rows: Int) : BridgeEvent()

    class Closed(val reason: String) : BridgeEvent()
}
