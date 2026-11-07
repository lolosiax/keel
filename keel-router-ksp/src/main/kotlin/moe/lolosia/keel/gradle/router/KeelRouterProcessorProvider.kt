package moe.lolosia.keel.gradle.router

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated

/**
 * Web 路由生成器的 KSP 入口。
 *
 * 目标形态：扫描插件里的控制器注解，生成路由注册表（无反射），
 * 与 `lolosia-backend` 的 `RouterProcessor` 同源。
 *
 * 当前为占位实现：不产生任何文件，仅验证处理器能被 KSP 加载。
 */
class KeelRouterProcessorProvider : SymbolProcessorProvider {

    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        KeelRouterProcessor()
}

private class KeelRouterProcessor : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> = emptyList()
}
