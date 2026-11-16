package moe.lolosia.keel.idea.debug

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValueChildrenList

/**
 * 一个 Keel 栈帧。
 *
 * 文本与日志里的异常帧一致：`包.类.函数(File.kt:行)`；
 * 双击可跳到对应源码，因为 [getSourcePosition] 返回真实的 PSI 位置。
 *
 * 只有栈顶帧带变量快照：运行期在命中瞬间抓下 `this` / 参数 / 当前闭包捕获的预览，
 * 值可逐层展开（集合、Map、数组、`Throwable`、枚举，以及编译器生成 `DebugView` 的类）。
 */
class KeelStackFrame(
    private val project: Project,
    private val location: KeelStackLocation,
    private val values: List<KeelValueSnapshot> = emptyList(),
    /** 有变量时非空：子成员由它按需向运行期要。 */
    private val memberSource: KeelMemberSource? = null,
    /** 跨 image 拼进来的帧：把模块名标出来，否则 Host 与插件的帧看起来像同一个栈。 */
    private val showModule: Boolean = false,
) : XStackFrame() {

    /** 解析一次就够；索引查询需要读锁，平台会在后台协程里要这个位置。 */
    private val resolvedPosition: XSourcePosition? by lazy {
        ReadAction.compute<XSourcePosition?, RuntimeException> {
            val file = findVirtualFile() ?: return@compute null
            XDebuggerUtil.getInstance().createPosition(file, (location.line - 1).coerceAtLeast(0))
        }
    }

    override fun getSourcePosition(): XSourcePosition? = resolvedPosition

    override fun customizePresentation(container: ColoredTextContainer) {
        container.append(location.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        container.append(" (${location.fileLabel})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        if (showModule) {
            container.append("  [${location.module}]", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    override fun computeChildren(node: XCompositeNode) {
        if (values.isEmpty()) {
            super.computeChildren(node)
            return
        }
        val children = XValueChildrenList(values.size)
        values.forEach { snapshot -> children.add(snapshot.name, KeelObjectValue(snapshot, memberSource)) }
        node.addChildren(children, true)
    }

    private fun findVirtualFile(): VirtualFile? {
        if (location.file.isEmpty()) return null
        LocalFileSystem.getInstance().findFileByPath(location.file)?.let { return it }
        val name = location.file.substringAfterLast('/')
        if (name.isEmpty()) return null
        val suffix = "/" + location.file.trimStart('/')
        // 位置表里的路径相对 Gradle 工作区；Host 源码可能不在当前模块，用全工程范围找
        return FilenameIndex.getFilesByName(project, name, GlobalSearchScope.allScope(project))
            .asSequence()
            .mapNotNull { it.virtualFile }
            .firstOrNull { it.path.replace('\\', '/').endsWith(suffix) }
    }
}
