package moe.lolosia.keel.idea.debug

import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.XStackFrame

/**
 * 栈里的分隔行：不是真实帧，只用来标注下一段帧的来源。
 *
 * IDEA 的协程视图在「当前协程的帧」和「协程创建栈」之间也有这样一行；
 * 平台内部的 `KotlinCoroutineStackFrame` 依赖 JVM 侧协程调试代理，这里没有，
 * 用一条不可跳转的普通帧渲染同样的效果。
 */
class KeelSeparatorFrame(private val label: String) : XStackFrame() {

    override fun getSourcePosition(): XSourcePosition? = null

    override fun customizePresentation(container: ColoredTextContainer) {
        container.append(label, SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}
