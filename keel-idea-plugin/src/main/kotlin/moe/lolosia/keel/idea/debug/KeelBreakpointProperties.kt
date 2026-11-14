package moe.lolosia.keel.idea.debug

import com.intellij.xdebugger.breakpoints.XBreakpointProperties

/**
 * Keel 行断点的附加属性。
 *
 * 当前没有可配置项；保留独立类型是为了后续加条件、命中次数、日志表达式时
 * 不改变断点的持久化结构。
 */
class KeelBreakpointProperties : XBreakpointProperties<KeelBreakpointProperties>() {

    override fun getState(): KeelBreakpointProperties = this

    override fun loadState(state: KeelBreakpointProperties) = Unit
}
