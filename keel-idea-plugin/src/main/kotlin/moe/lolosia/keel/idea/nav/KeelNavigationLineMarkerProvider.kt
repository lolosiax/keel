package moe.lolosia.keel.idea.nav

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import javax.swing.Icon

/**
 * Keel 自己的上下箭头，与 Kotlin 原生图标并存。
 *
 * 原生图标保持原生行为（父声明会停在 api 镜像）；这两个图标只做 Keel 的跳转：
 * - 下箭头：到真实实现（过滤 ABI 胶水）；
 * - 上箭头：到父声明对应的 impl（镜像 → impl）。
 */
class KeelNavigationLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        elements.forEach { element ->
            val anchor = anchorOf(element) ?: return@forEach
            val markers = ReadAction.compute<List<LineMarkerInfo<*>>, RuntimeException> {
                buildMarkers(element, anchor)
            }
            result += markers
        }
    }

    private fun anchorOf(element: PsiElement): PsiElement? = when (element) {
        is KtClassOrObject -> element.nameIdentifier
        is KtNamedFunction -> element.nameIdentifier
        is KtProperty -> element.nameIdentifier
        else -> null
    }

    private fun buildMarkers(element: PsiElement, anchor: PsiElement): List<LineMarkerInfo<*>> {
        val markers = mutableListOf<LineMarkerInfo<*>>()

        KeelPublicApiBridges.cachedImplementationsOf(element)
            ?.takeIf { it.isNotEmpty() }
            ?.let { targets ->
                markers += marker(KeelIcons.Implemented, anchor, targets, "Keel: implemented in")
            }

        val isOverrideMember =
            (element as? KtModifierListOwner)?.hasModifier(KtTokens.OVERRIDE_KEYWORD) == true
        if (element is KtClassOrObject || isOverrideMember) {
            KeelPublicApiBridges.cachedSuperTargets(element)
                .takeIf { it.isNotEmpty() }
                ?.let { targets ->
                    markers += marker(KeelIcons.Overridden, anchor, targets, "Keel: overrides")
                }
        }
        return markers
    }

    private fun marker(
        icon: Icon,
        anchor: PsiElement,
        targets: List<PsiElement>,
        title: String,
    ): LineMarkerInfo<*> = NavigationGutterIconBuilder.create(icon)
        .setTargets(targets)
        .setTooltipText(title + " " + targets.joinToString { (it as? PsiNamedElement)?.name ?: "?" })
        .createLineMarkerInfo(anchor)
}
