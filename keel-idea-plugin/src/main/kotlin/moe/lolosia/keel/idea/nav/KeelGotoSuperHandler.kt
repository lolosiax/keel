package moe.lolosia.keel.idea.nav

import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.lang.LanguageExtension
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtDeclaration

private val LOG = Logger.getInstance("moe.lolosia.keel.idea.nav")

private const val GOTO_SUPER_EP = "com.intellij.codeInsight.gotoSuper"

/**
 * Ctrl+U（转到父方法/父类）的 Keel 桥。
 *
 * Kotlin 的默认 handler 按描述符解析父声明，而消费方真正继承的是 api 镜像
 * （方法体是 `PublicApiBridge.call` 的桩），所以会停在镜像上。这里把父声明里的镜像
 * 映射回 impl，命中时自己跳；没有命中则原样委托给 Kotlin 的 handler。
 */
class KeelGotoSuperHandler : CodeInsightActionHandler {

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val targets = ReadAction.compute<List<PsiElement>, RuntimeException> {
            resolveSuperTargets(project, editor, file)
        }
        LOG.info(
            "keel-nav super: targets=" +
                targets.map { "${(it as? PsiNamedElement)?.name ?: it.text.take(40)}@${it.containingFile?.name}" },
        )
        if (targets.isEmpty()) {
            delegate(project, editor, file)
            return
        }
        if (targets.size == 1) {
            (targets.first() as? Navigatable)?.navigate(true)
        } else {
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(targets)
                .setTitle("Choose Super Implementation")
                .setItemChosenCallback { (it as? Navigatable)?.navigate(true) }
                .createPopup()
                .showInBestPositionFor(editor)
        }
    }

    override fun startInWriteAction(): Boolean = false

    private fun resolveSuperTargets(project: Project, editor: Editor, file: PsiFile): List<PsiElement> = try {
        val source = resolveTarget(project, editor, file)
        LOG.info("keel-nav super: source=${source?.javaClass?.simpleName} text=${source?.text?.take(40)}")
        if (source == null) emptyList() else KeelPublicApiBridges.superTargetsOf(source)
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Throwable) {
        LOG.warn("keel-nav: goto super failed", e)
        emptyList()
    }

    /** 先按引用解析，解析不出时退回「光标所在的最内层 Kotlin 声明」。 */
    private fun resolveTarget(project: Project, editor: Editor, file: PsiFile): PsiElement? {
        val offset = editor.caretModel.offset
        val resolved = GotoDeclarationAction.findTargetElement(project, editor, offset)
        val leaf = file.findElementAt(offset)
        val declaration = PsiTreeUtil.getParentOfType(leaf, KtDeclaration::class.java, false)
        return (resolved ?: declaration)?.let(KeelPublicApiBridges::sourceOf)
    }

    /** 交给 Kotlin 自己的 gotoSuper handler（排除本类，避免递归）。 */
    private fun delegate(project: Project, editor: Editor, file: PsiFile) {
        val handler = LanguageExtension<CodeInsightActionHandler>(GOTO_SUPER_EP)
            .allForLanguage(file.language)
            .firstOrNull { it !is KeelGotoSuperHandler } ?: return
        handler.invoke(project, editor, file)
    }
}
