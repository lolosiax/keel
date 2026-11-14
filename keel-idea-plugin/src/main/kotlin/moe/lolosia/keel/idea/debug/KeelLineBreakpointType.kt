package moe.lolosia.keel.idea.debug

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import moe.lolosia.keel.idea.KeelIcons
import javax.swing.Icon

/**
 * Keel 行断点。
 *
 * 位置表按「文件 + 行」匹配，所以只允许在 Kotlin 源文件上打点；
 * 真正能不能停由运行期插桩点决定，打点时不做拦截。
 */
class KeelLineBreakpointType : XLineBreakpointType<KeelBreakpointProperties>(
    "keel-line",
    "Keel Line Breakpoint",
) {

    override fun createBreakpointProperties(file: VirtualFile, line: Int): KeelBreakpointProperties =
        KeelBreakpointProperties()

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean =
        file.extension == "kt"

    override fun getEnabledIcon(): Icon = KeelIcons.RunConfiguration
}
