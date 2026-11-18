package moe.lolosia.keel.idea.nav

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor

private val LOG = Logger.getInstance("moe.lolosia.keel.idea.nav")

/**
 * `com.intellij.definitionsSearch` 层的 Keel 桥。
 *
 * 平台在 `DefinitionsScopedSearch` 的静态初始化里注册了一个适配器，把实现搜索转发到
 * 这个 EP；Kotlin / Java 的继承搜索通常挂在这里。抢先返回 `false` 可以阻止它们把
 * `*Proxy` 之类的胶水类塞进结果，只留下 [KeelPublicApiBridges] 给出的真实实现。
 */
class KeelDefinitionsSearch : QueryExecutor<PsiElement, PsiElement> {

    override fun execute(element: PsiElement, consumer: Processor<in PsiElement>): Boolean =
        ReadAction.compute<Boolean, RuntimeException> {
            val publicApi = KeelPublicApiBridges.isPublicApi(element)
            LOG.info("keel-nav search entry: ${element.javaClass.simpleName} publicApi=$publicApi text=${element.text.take(60)}")
            if (!publicApi) return@compute true

            val targets = KeelPublicApiBridges.implementationsOf(element) ?: return@compute true
            // 桥接失败时退回内置搜索，避免把原本能跳的结果也屏蔽掉
            if (targets.isEmpty()) return@compute true
            for (target in targets) {
                if (!consumer.process(target)) return@compute false
            }
            false
        }
}
