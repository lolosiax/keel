package moe.lolosia.keel.idea.debug

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProviderBase

/**
 * 表达式编辑器的占位实现。
 *
 * M0–M2 不支持求值，Watch / Evaluate 面板会退化成纯文本编辑器；
 * 提供实例而不是 null，是为了避免平台在打开求值对话框时直接 NPE。
 */
class KeelEditorsProvider : XDebuggerEditorsProviderBase() {

    override fun getFileType(): FileType = PlainTextFileType.INSTANCE

    override fun createExpressionCodeFragment(
        project: Project,
        text: String,
        contextElement: PsiElement?,
        isPhysical: Boolean,
    ): PsiFile? = null
}
