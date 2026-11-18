package moe.lolosia.keel.idea.nav

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.findUsages.KotlinFindUsagesSupport
import org.jetbrains.kotlin.idea.stubindex.KotlinFullClassNameIndex
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtUserType

private val LOG = Logger.getInstance("moe.lolosia.keel.idea.nav")

/**
 * `@PublicApi` 的 impl 与 api 镜像桥。
 *
 * impl 与 api 里的同名类型是两个 FQN 相同、PSI 身份不同的类：消费方（如 dog）实现的是
 * api 镜像，而声明方导航时看到的是 impl 类，所以 IntelliJ 自带的「转到实现」只能找到
 * 声明方自己的胶水代理。这里按 FQN 把两侧的同名类型串起来，再收集各自的真实子类。
 *
 * 继承搜索有两条来源：
 * - Kotlin 自己的 [KotlinFindUsagesSupport.searchInheritors]（K2 下由 Stub/FIR 索引驱动，
 *   平台 `ClassInheritorsSearch` 看不到 Kotlin 源码继承）；
 * - 平台 [ClassInheritorsSearch]，用于 Java 实现。
 *
 * 继承搜索返回的可能是 light class（Java 视图），suspend 函数的 light 方法会多出
 * `Continuation` 参数，因此方法匹配一律回到 Kotlin 源码 PSI 上做。
 *
 * 生成物（`*Proxy` / `*Shadow` / `*Dispatcher` / `*Overrides` / `*Accessor` / `*Key`）
 * 不计入结果：它们是 ABI 胶水，不是业务实现。
 */
object KeelPublicApiBridges {

    private const val PUBLIC_API_FQN = "moe.lolosia.keel.api.annotation.PublicApi"

    private val GENERATED_SUFFIXES = listOf("Proxy", "Shadow", "Dispatcher", "Overrides", "Accessor", "Key")

    /** 元素本身是否指向 `@PublicApi` 声明的类型（类、接口，或它们的成员）。 */
    fun isPublicApi(element: PsiElement): Boolean = publicApiFqn(element) != null

    /** 行内图标用：结果按 PSI 修改计数缓存，避免每次高亮都做全工程搜索。 */
    fun cachedImplementationsOf(element: PsiElement): List<PsiElement>? =
        CachedValuesManager.getCachedValue(element) {
            CachedValueProvider.Result.create(
                implementationsOf(element),
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }

    /** 行内图标用：父声明里映射回 impl 的目标（类级或成员级）。 */
    fun cachedSuperTargets(element: PsiElement): List<PsiElement> =
        CachedValuesManager.getCachedValue(element) {
            CachedValueProvider.Result.create(
                superTargetsOf(element),
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }

    /** 父声明里凡是 api 镜像就换成 impl：类返回 impl 类，成员返回 impl 成员。 */
    fun superTargetsOf(element: PsiElement): List<PsiElement> = when (element) {
        is KtClassOrObject -> superImplTargets(element)
        is KtDeclaration -> {
            val owner = PsiTreeUtil.getParentOfType(element, KtClassOrObject::class.java, false)
            if (owner == null) emptyList() else superMemberImplTargets(owner, element)
        }

        else -> emptyList()
    }

    fun superImplTargets(owner: KtClassOrObject): List<PsiElement> =
        superClassSources(owner)
            .filter(::isApiMirror)
            .mapNotNull(::implDeclarationOf)
            .distinct()

    fun superMemberImplTargets(owner: KtClassOrObject, member: KtDeclaration): List<PsiElement> =
        superClassSources(owner)
            .filter(::isApiMirror)
            .mapNotNull { mirror -> implDeclarationOf(mirror)?.let { memberOrNull(it, member) } }
            .distinct()

    /** 收集父类型的源码声明：类型引用解析 → import/包名拼 FQN 查索引 → light class 兜底。 */
    fun superClassSources(owner: KtClassOrObject): List<KtClassOrObject> {
        val result = LinkedHashSet<KtClassOrObject>()
        val project = owner.project

        owner.superTypeListEntries.forEach { entry ->
            val type = entry.typeReference?.typeElement as? KtUserType ?: return@forEach
            val simpleName = type.referencedName ?: return@forEach

            (guarded { type.reference?.resolve() }?.let(::sourceOf) as? KtClassOrObject)?.let(result::add)
            fqnCandidates(owner, simpleName).forEach { fqn ->
                declarationsOf(fqn, project).filterTo(result) { it.name == simpleName }
            }
        }

        guarded { owner.toLightClass() }?.superTypes?.forEach { type ->
            val superClass = guarded { type.resolve() } ?: return@forEach
            (sourceOf(superClass) as? KtClassOrObject)?.let(result::add)
        }
        return result.toList()
    }

    /** `Animal` → 文件里 import 的 `moe...Animal`；同时兜底同包声明与星号导入。 */
    private fun fqnCandidates(owner: KtClassOrObject, simpleName: String): List<String> {
        val file = owner.containingKtFile
        val result = LinkedHashSet<String>()
        file.importDirectives.forEach { directive ->
            val fq = directive.importedFqName?.asString() ?: return@forEach
            when {
                directive.isAllUnder -> result += "$fq.$simpleName"
                fq.substringAfterLast('.') == simpleName -> result += fq
            }
        }
        val pkg = file.packageFqName.asString()
        result += if (pkg.isEmpty()) simpleName else "$pkg.$simpleName"
        return result.toList()
    }

    private fun <T> guarded(block: () -> T?): T? = try {
        block()
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Throwable) {
        LOG.warn("keel-nav: resolve failed", e)
        null
    }

    /**
     * 收集与 [element] 对应的真实实现。
     *
     * @return `null` 表示不是 `@PublicApi`（或找不到同 FQN 的对端），调用方应让内置搜索继续；
     *         空列表表示确实是 `@PublicApi` 但当前没有真实实现。
     */
    fun implementationsOf(element: PsiElement): List<PsiElement>? {
        val fqn = publicApiFqn(element) ?: return null
        val target = element.sourceOrSelf()
        val project = element.project
        val scope = GlobalSearchScope.projectScope(project)
        val methodName = target.methodName()
        val arity = target.methodArity()

        // 同 FQN 的每一份源码（impl 与 api 镜像）都要作为搜索起点：K2 的继承搜索按符号身份
        // 解析，只搜当前 PSI 那一份会漏掉实现对端。Kotlin 索引能可靠地给出全部同名声明，
        // JavaPsiFacade.findClasses 只会返回其中一个。
        val declarations = LinkedHashSet<PsiElement>()
        ktDeclarationOf(target)?.let(declarations::add)
        declarations += kotlinDeclarationsOf(fqn, project, scope)
        val lightClasses = LinkedHashSet<PsiClass>()
        counterpartsOf(fqn, project, scope).forEach { counterpart ->
            lightClasses += counterpart
            declarations += counterpart.sourceOrSelf()
        }

        val inheritors = LinkedHashSet<PsiElement>()
        declarations.forEach { declaration ->
            tryOrLog("keel-nav: kotlin inheritors failed fqn=$fqn decl=${declaration.nameOrText()}") {
                KotlinFindUsagesSupport.searchInheritors(declaration, scope, true)
                    .forEach { inheritors += it.sourceOrSelf() }
            }
        }
        lightClasses.forEach { lightClass ->
            tryOrLog("keel-nav: java inheritors failed fqn=$fqn") {
                ClassInheritorsSearch.search(lightClass, scope, true).findAll()
                    .forEach { inheritors += it.sourceOrSelf() }
            }
        }

        val result = LinkedHashSet<PsiElement>()
        inheritors.forEach { inheritor ->
            if (inheritor in declarations || isGenerated(inheritor)) return@forEach
            if (methodName == null) {
                result += inheritor
            } else {
                inheritor.methodOrNull(methodName, arity)?.let(result::add)
            }
        }

        // 函数自己再做一次 override 搜索：覆盖实现在 @PublicApi 继承链之外声明的情况
        if (target is KtNamedFunction) {
            tryOrLog("keel-nav: kotlin overriders failed fqn=$fqn") {
                KotlinFindUsagesSupport.searchOverriders(target, scope).forEach { overriding ->
                    val source = overriding.sourceOrSelf()
                    if (!isGenerated(source) && (source is KtNamedFunction || source is PsiMethod)) result += source
                }
            }
        }

        LOG.info(
            "keel-nav: fqn=$fqn element=${element.javaClass.simpleName} method=$methodName arity=$arity " +
                "declarations=${declarations.map { it.nameAndFile() }} inheritors=${inheritors.map { it.nameAndFile() }} " +
                "results=${result.map { it.nameAndFile() }}",
        )
        return result.toList()
    }

    private fun ktDeclarationOf(element: PsiElement): KtClassOrObject? = when (val target = element.sourceOrSelf()) {
        is KtClassOrObject -> target
        is KtNamedFunction -> PsiTreeUtil.getParentOfType(target, KtClassOrObject::class.java, false)
        else -> null
    }

    private fun publicApiFqn(element: PsiElement): String? {
        val target = element.sourceOrSelf()
        return when (target) {
            is KtClassOrObject ->
                target.fqName?.asString()?.takeIf { target.hasPublicApiAnnotation() }

            is KtNamedFunction -> PsiTreeUtil
                .getParentOfType(target, KtClassOrObject::class.java, false)
                ?.let { owner -> owner.fqName?.asString()?.takeIf { owner.hasPublicApiAnnotation() } }

            is PsiClass ->
                target.qualifiedName?.takeIf { target.hasAnnotation(PUBLIC_API_FQN) }

            is PsiMethod ->
                target.containingClass
                    ?.takeIf { it.hasAnnotation(PUBLIC_API_FQN) }
                    ?.qualifiedName

            else -> null
        }
    }

    private fun KtClassOrObject.hasPublicApiAnnotation(): Boolean =
        annotationEntries.any { it.shortName?.asString() == "PublicApi" }

    private fun PsiClass.hasAnnotation(fqn: String): Boolean =
        modifierList?.findAnnotation(fqn) != null

    private fun PsiElement.methodName(): String? = when (this) {
        is KtNamedFunction -> name
        is PsiMethod -> name
        else -> null
    }

    private fun PsiElement.methodArity(): Int? = when (this) {
        is KtNamedFunction -> valueParameters.size
        is PsiMethod -> parameterList.parametersCount
        else -> null
    }

    /** 继承/重写搜索的结果可能是 light 元素；跳到它对应的源码 PSI 再匹配。 */
    private fun PsiElement.sourceOrSelf(): PsiElement {
        val origin = (this as? KtLightElement<*, *>)?.kotlinOrigin
        if (origin is PsiElement && origin != this) return origin

        val navigation = navigationElement ?: return this
        return if (navigation is KtClassOrObject || navigation is KtNamedFunction ||
            navigation is PsiClass || navigation is PsiMethod
        ) {
            navigation
        } else {
            this
        }
    }

    private fun PsiElement.methodOrNull(name: String, arity: Int?): PsiElement? = when (this) {
        is KtClassOrObject -> declarations
            .filterIsInstance<KtNamedFunction>()
            .firstOrNull { it.name == name && (arity == null || it.valueParameters.size == arity) }

        is PsiClass -> findMethodsByName(name, true)
            .asSequence()
            .map { it.sourceOrSelf() }
            .firstOrNull { candidate ->
                when (candidate) {
                    is KtNamedFunction -> candidate.name == name &&
                        (arity == null || candidate.valueParameters.size == arity)

                    is PsiMethod -> arity == null || candidate.parameterList.parametersCount == arity
                    else -> false
                }
            }

        else -> null
    }

    private fun counterpartsOf(fqn: String, project: Project, scope: GlobalSearchScope): List<PsiClass> =
        runCatching {
            JavaPsiFacade.getInstance(project).findClasses(fqn, scope).toList()
        }.getOrDefault(emptyList())

    /**
     * 全部同 FQN 的 Kotlin 声明（impl 与 api 镜像各一份）。
     *
     * impl 与 api 镜像的 FQN 相同，但 K2 的继承搜索按符号身份解析：从 impl 声明出发只能
     * 找到声明方自己的生成物，必须再从 api 镜像声明出发才能找到消费方的真实实现。
     */
    private fun kotlinDeclarationsOf(fqn: String, project: Project, scope: GlobalSearchScope): List<KtClassOrObject> =
        tryOrLog("keel-nav: kotlin class index failed fqn=$fqn") {
            KotlinFullClassNameIndex.get(fqn, project, scope).toList()
        }.orEmpty()

    private fun isGenerated(target: PsiElement): Boolean {
        val path = target.containingFile?.virtualFile?.path
        if (path != null && (path.contains("/build/generated/") || path.contains("/generated/ksp/"))) return true
        val name = (target as? PsiNamedElement)?.name ?: return false
        return GENERATED_SUFFIXES.any { name.endsWith(it) }
    }

    private fun PsiElement.nameOrText(): String =
        (this as? PsiNamedElement)?.name ?: text.take(60)

    /** 把 light 元素映射回源码 PSI；已经是源码时原样返回。 */
    fun sourceOf(element: PsiElement): PsiElement = element.sourceOrSelf()

    /** 是否是 `-api` 子工程里的镜像声明（impl 的对端，方法体是 `PublicApiBridge.call`）。 */
    fun isApiMirror(element: PsiElement): Boolean {
        val path = element.containingFile?.virtualFile?.path ?: return false
        return path.contains("/api/src/") || path.contains("\\api\\src\\")
    }

    /** 按 FQN 取全部 Kotlin 声明（impl 与 api 镜像都会返回）。 */
    fun declarationsOf(fqn: String, project: Project): List<KtClassOrObject> =
        kotlinDeclarationsOf(fqn, project, GlobalSearchScope.projectScope(project))

    /** 同一 FQN 下与 [mirror] 对应的 impl 声明。 */
    fun implDeclarationOf(mirror: KtClassOrObject): KtClassOrObject? {
        val fqn = mirror.fqName?.asString() ?: return null
        return declarationsOf(fqn, mirror.project)
            .firstOrNull { it != mirror && !isApiMirror(it) }
    }

    /** 在 [owner] 里找与 [member] 同名同形的成员，用于从镜像成员映射到 impl 成员。 */
    fun memberOrNull(owner: KtClassOrObject, member: KtDeclaration): PsiElement? = when (member) {
        is KtNamedFunction -> owner.declarations
            .filterIsInstance<KtNamedFunction>()
            .firstOrNull { it.name == member.name && it.valueParameters.size == member.valueParameters.size }

        is KtProperty -> owner.declarations
            .filterIsInstance<KtProperty>()
            .firstOrNull { it.name == member.name }

        else -> null
    }

    private fun PsiElement.nameAndFile(): String =
        "${(this as? PsiNamedElement)?.name ?: text.take(40)}@${containingFile?.name}"

    /** 搜索失败不应中断「转到实现」；但取消异常必须原样抛出。 */
    private inline fun <T> tryOrLog(message: String, block: () -> T): T? = try {
        block()
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Throwable) {
        LOG.warn(message, e)
        null
    }
}
