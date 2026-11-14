package moe.lolosia.keel.idea.debug

import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XSuspendContext

/** 一次暂停：当前只包含命中断点的那个线程，M2 再扩展成多线程。 */
class KeelSuspendContext(
    private val stacks: List<KeelExecutionStack>,
) : XSuspendContext() {

    override fun getActiveExecutionStack(): XExecutionStack = stacks.first()

    override fun getExecutionStacks(): Array<XExecutionStack> = stacks.toTypedArray()
}
