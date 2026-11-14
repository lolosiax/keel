package moe.lolosia.keel.idea.debug

import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame

/**
 * 一个运行期线程的栈。
 *
 * Keel 的 Host 与每个插件是独立运行时，所以线程名要带模块前缀，
 * 否则同一个 `thread 1` 会在面板里出现多次且无法区分。
 */
class KeelExecutionStack(
    displayName: String,
    private val frames: List<XStackFrame>,
) : XExecutionStack(displayName) {

    override fun getTopFrame(): XStackFrame? = frames.firstOrNull()

    override fun computeStackFrames(index: Int, container: XExecutionStack.XStackFrameContainer) {
        container.addStackFrames(frames, true)
    }
}
