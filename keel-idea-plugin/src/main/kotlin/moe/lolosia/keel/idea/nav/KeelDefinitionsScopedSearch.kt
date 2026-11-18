package moe.lolosia.keel.idea.nav

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor

private val LOG = Logger.getInstance("moe.lolosia.keel.idea.nav")

/**
 * 「转到实现」（Ctrl+Alt+B）的 Keel 桥。
 *
 * 平台把实现搜索交给 `com.intellij.definitionsScopedSearch`；Kotlin 的执行器只认当前
 * PSI 类，因此 `@PublicApi` 在 impl / api 两侧互相看不到对方的真实实现。这里接管
 * `@PublicApi` 元素：先按 FQN 桥接到对端、只交出业务实现（过滤 ABI 胶水），
 * 再返回 `false` 结束后续执行器——尽量把内置结果里的 `*Proxy` 也挤掉。
 */
class KeelDefinitionsScopedSearch : QueryExecutor<PsiElement, DefinitionsScopedSearch.SearchParameters> {

    override fun execute(
        queryParameters: DefinitionsScopedSearch.SearchParameters,
        consumer: Processor<in PsiElement>,
    ): Boolean = ReadAction.compute<Boolean, RuntimeException> {
        val element = queryParameters.element
        val publicApi = KeelPublicApiBridges.isPublicApi(element)
        LOG.info("keel-nav scoped entry: ${element.javaClass.simpleName} publicApi=$publicApi text=${element.text.take(60)}")
        if (!publicApi) return@compute true

        val targets = KeelPublicApiBridges.implementationsOf(element) ?: return@compute true
        // 桥接失败时退回内置搜索，避免把原本能跳的结果也屏蔽掉
        if (targets.isEmpty()) return@compute true
        for (target in targets) {
            if (!consumer.process(target)) return@compute false
        }
        // 已由我们给出权威结果：不再让 Kotlin / Java 的继承搜索补上胶水代理
        false
    }
}
