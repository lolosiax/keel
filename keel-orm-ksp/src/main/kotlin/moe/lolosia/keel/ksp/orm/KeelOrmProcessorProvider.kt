package moe.lolosia.keel.ksp.orm

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.Visibility
import com.google.devtools.ksp.validate

private const val TABLE_ANNOTATION = "moe.lolosia.keel.orm.annotation.Table"
private const val ID_ANNOTATION = "moe.lolosia.keel.orm.annotation.Id"
private const val GENERATED_ANNOTATION = "moe.lolosia.keel.orm.annotation.Generated"
private const val COLUMN_ANNOTATION = "moe.lolosia.keel.orm.annotation.Column"
private const val TRANSIENT_ANNOTATION = "moe.lolosia.keel.orm.annotation.Transient"

/** 引导安装器的固定落点；`keel-plugin-ksp` 生成的 Worker 入口按这个 FQN 调用。 */
private const val BOOTSTRAP_PACKAGE = "moe.lolosia.keel.orm.generated"

/** 构建期方言；由 `moe.lolosia.keel.database` 通过 `ksp { arg(...) }` 传入。 */
private const val DIALECT_OPTION = "keel.orm.dialect"

/**
 * 生成物落点；存在时直接写进源码目录（V1 提交制），而不是 KSP 输出目录。
 *
 * 多 native 目标下，`nativeMain` 会参与 metadata 编译，而 KSP 只把生成物放进
 * 各目标的编译输出；只有写进 `src/nativeMain/kotlin`（或同等共享源集）才能被
 * 元数据编译与所有目标同时看到。`moe.lolosia.keel.database` 默认就会传这个参数。
 */
private const val ORM_DIR_OPTION = "keel.orm.dir"

/** 生成物首行；写进源码目录时用它区分「生成文件」与用户手写文件。 */
private const val HEADER = "// 由 keel-orm-ksp 生成，不要手改。"

/**
 * 实体生成器：`@Table` → `<Entity>Meta`、`<Entity>Columns`、`OrmMetaBootstrap`。
 *
 * 生成的列扩展落在实体所在包，查询代码与实体同包时不需要额外 import。
 * 引导安装器是聚合产物（`aggregating = true`），每个编译目标各生成一份。
 */
class KeelOrmProcessorProvider : SymbolProcessorProvider {

    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        KeelOrmProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
            options = environment.options,
        )
}

private class KeelOrmProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {

    private var generated = false

    private val outputDir: java.io.File? =
        options[ORM_DIR_OPTION]?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()

        val dialect = resolveDialect() ?: return emptyList()

        val symbols = resolver.getSymbolsWithAnnotation(TABLE_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        // 还没解析完的符号留到下一轮，避免误报「实体缺列 / 缺 @Id」
        val pending = symbols.filterNot { it.validate() }
        if (pending.isNotEmpty()) return pending

        val entities = symbols.mapNotNull { parse(it) }
        entities.forEach { generateEntity(it) }
        generateBootstrap(entities, dialect)
        generated = true
        return emptyList()
    }

    private fun resolveDialect(): String? {
        val raw = options[DIALECT_OPTION]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "sqlite"
        return when (raw) {
            "sqlite" -> "SqliteDialect"
            "mariadb", "mysql" -> "MariaDbDialect"
            else -> {
                logger.error("未知的 keel.orm.dialect=$raw；只支持 sqlite / mariadb")
                null
            }
        }
    }

    private class FieldModel(
        val property: String,
        val column: String,
        val kotlinType: String,
        val ormType: String,
        val nullable: Boolean,
        val id: Boolean,
        val generated: Boolean,
    )

    private class EntityModel(
        val declaration: KSClassDeclaration,
        val pkg: String,
        val simpleName: String,
        val table: String,
        val fields: List<FieldModel>,
    )

    private fun parse(declaration: KSClassDeclaration): EntityModel? {
        if (declaration.classKind != ClassKind.CLASS) {
            logger.error("@Table 只能标在普通 class 上", declaration)
            return null
        }
        if (declaration.getVisibility() != Visibility.PUBLIC) {
            logger.error("实体类必须是 public（去掉 private / internal）", declaration)
            return null
        }
        val table = declaration.stringArgument(TABLE_ANNOTATION)
        if (table.isNullOrBlank()) {
            logger.error("@Table 需要显式表名", declaration)
            return null
        }

        val fields = mutableListOf<FieldModel>()
        var id: FieldModel? = null

        for (owner in hierarchy(declaration)) {
            for (property in owner.declarations.filterIsInstance<KSPropertyDeclaration>()) {
                if (property.hasAnnotation(TRANSIENT_ANNOTATION)) continue
                if (property.getVisibility() == Visibility.PRIVATE) {
                    logger.error("实体列属性不能是 private：${property.simpleName.asString()}", property)
                    continue
                }
                if (!property.isMutable) {
                    logger.error("实体列属性必须是 var：${property.simpleName.asString()}", property)
                    continue
                }

                val resolved = property.type.resolve()
                val mapping = typeMapping(resolved)
                if (mapping == null) {
                    logger.error(
                        "无法映射的属性类型：${resolved.declaration.simpleName.asString()}" +
                            "（${property.simpleName.asString()}）",
                        property,
                    )
                    continue
                }

                val isId = property.hasAnnotation(ID_ANNOTATION)
                val field = FieldModel(
                    property = property.simpleName.asString(),
                    column = property.columnName() ?: snakeCase(property.simpleName.asString()),
                    kotlinType = mapping.first,
                    ormType = mapping.second,
                    nullable = resolved.isMarkedNullable,
                    id = isId,
                    generated = isId && property.hasAnnotation(GENERATED_ANNOTATION),
                )
                if (isId) {
                    if (id != null) {
                        logger.error("实体只能有一个 @Id", property)
                        return null
                    }
                    if (field.nullable) {
                        logger.error("@Id 不能是可空类型", property)
                        return null
                    }
                    id = field
                }
                fields += field
            }
        }

        if (id == null) {
            logger.error("实体缺少 @Id：${declaration.simpleName.asString()}", declaration)
            return null
        }
        if (fields.isEmpty()) {
            logger.error("实体没有可映射的列：${declaration.simpleName.asString()}", declaration)
            return null
        }

        return EntityModel(
            declaration = declaration,
            pkg = declaration.packageName.asString(),
            simpleName = declaration.simpleName.asString(),
            table = table,
            fields = fields,
        )
    }

    /** 沿 superclass 链收集字段；基类在前，`Any` 停止。 */
    private fun hierarchy(declaration: KSClassDeclaration): List<KSClassDeclaration> {
        val chain = mutableListOf<KSClassDeclaration>()
        var current: KSClassDeclaration? = declaration
        while (current != null) {
            val qualified = current.qualifiedName?.asString()
            if (qualified == null || qualified == "kotlin.Any") break
            chain += current
            current = current.superTypes
                .mapNotNull { it.resolve().declaration as? KSClassDeclaration }
                .firstOrNull { it.classKind == ClassKind.CLASS }
        }
        return chain.reversed()
    }

    /** Kotlin 类型 → (列扩展的类型名, `OrmTypes` 表达式)；不支持时返回 null。 */
    private fun typeMapping(type: KSType): Pair<String, String>? {
        val qualified = type.declaration.qualifiedName?.asString() ?: return null
        return when (qualified) {
            "kotlin.String" -> "String" to "OrmTypes.TEXT"
            "kotlin.Int" -> "Int" to "OrmTypes.INT32"
            "kotlin.Long" -> "Long" to "OrmTypes.INT"
            "kotlin.Double" -> "Double" to "OrmTypes.REAL"
            "kotlin.Boolean" -> "Boolean" to "OrmTypes.BOOL"
            "kotlin.ByteArray" -> "ByteArray" to "OrmTypes.BLOB"
            "kotlin.time.Instant" -> "kotlin.time.Instant" to "OrmTypes.INSTANT"
            "moe.lolosia.keel.orm.Decimal" -> "moe.lolosia.keel.orm.Decimal" to "OrmTypes.DECIMAL"
            "kotlinx.serialization.json.JsonElement" ->
                "kotlinx.serialization.json.JsonElement" to "OrmTypes.JSON"

            else -> {
                val declaration = type.declaration as? KSClassDeclaration ?: return null
                if (declaration.classKind != ClassKind.ENUM_CLASS) return null
                qualified to "OrmTypes.enum<$qualified>()"
            }
        }
    }

    private fun generateEntity(entity: EntityModel) {
        val containing = entity.declaration.containingFile
        writeFile(entity.pkg, "${entity.simpleName}Meta", metaText(entity), containing)
        writeFile(entity.pkg, "${entity.simpleName}Columns", columnsText(entity), containing)
    }

    private fun writeFile(pkg: String, name: String, text: String, containing: KSFile?) {
        write(pkg, name, text, listOfNotNull(containing), aggregating = false)
    }

    private fun write(
        pkg: String,
        name: String,
        text: String,
        sources: List<KSFile>,
        aggregating: Boolean,
    ) {
        val dir = outputDir
        if (dir != null) {
            writeSourceFile(dir, pkg, name, text)
            return
        }
        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating, *sources.toTypedArray()),
            packageName = pkg,
            fileName = name,
        )
        file.write(text.encodeToByteArray())
    }

    /**
     * V1 提交制：直接写进共享源码目录。
     *
     * 已存在但不是生成物时不动它，避免覆盖用户手写的同名文件。
     */
    private fun writeSourceFile(root: java.io.File, pkg: String, name: String, text: String) {
        val dir = java.io.File(root, pkg.replace('.', '/'))
        dir.mkdirs()
        val file = java.io.File(dir, "$name.kt")
        if (file.isFile && !file.readText().startsWith(HEADER)) {
            logger.warn("ORM 生成物已存在且不是生成文件，跳过：${file.path}")
            return
        }
        // 多个 KSP 任务 / 并行构建可能同时写同一份生成物；用进程间文件锁串行化
        val lock = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "keel-orm-${root.absolutePath.hashCode()}.lock",
        )
        java.nio.channels.FileChannel.open(
            lock.toPath(),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                // 清理上一次被中断留下的临时文件
                dir.listFiles { entry ->
                    entry.isFile && entry.name.startsWith("$name") && entry.name.endsWith(".tmp")
                }?.forEach { it.delete() }
                val tmp = java.io.File.createTempFile(name, ".tmp", dir)
                tmp.writeText(text)
                java.nio.file.Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
    }

    private fun metaText(entity: EntityModel): String {
        val id = entity.fields.first { it.id }
        val insert = entity.fields.filterNot { it.id && it.generated }
        val update = entity.fields.filterNot { it.id }

        val reads = entity.fields.mapIndexed { index, field ->
            val accessor = if (field.nullable) "decodeOrNull" else "decode"
            "        ${field.property} = ${field.ormType}.$accessor(row, $index)"
        }.joinToString("\n")

        fun writes(fields: List<FieldModel>): String = fields.joinToString(",\n") { field ->
            val expression = if (field.nullable) {
                // 实体列是 var，不能直接 smart cast；用 let 取值
                "value.${field.property}?.let { ${field.ormType}.encode(it) } ?: DbValue.Null"
            } else {
                "${field.ormType}.encode(value.${field.property})"
            }
            "        $expression"
        }

        fun columns(fields: List<FieldModel>): String =
            fields.joinToString(", ") { "\"${it.column}\"" }

        return """
            |// 由 keel-orm-ksp 生成，不要手改。
            |package ${entity.pkg}
            |
            |import kotlin.reflect.KClass
            |import moe.lolosia.keel.database.DbValue
            |import moe.lolosia.keel.database.Row
            |import moe.lolosia.keel.orm.*
            |
            |object ${entity.simpleName}Meta : EntityMeta<${entity.simpleName}> {
            |    override val type: KClass<${entity.simpleName}> = ${entity.simpleName}::class
            |    override val table: String = "${entity.table}"
            |    override val columns: List<String> = listOf(${columns(entity.fields)})
            |    override val insertColumns: List<String> = listOf(${columns(insert)})
            |    override val updateColumns: List<String> = listOf(${columns(update)})
            |    override val idColumn: String = "${id.column}"
            |    override val generatedId: Boolean = ${id.generated}
            |
            |    override fun new(): ${entity.simpleName} = ${entity.simpleName}()
            |
            |    override fun read(row: Row): ${entity.simpleName} = ${entity.simpleName}().apply {
            |$reads
            |    }
            |
            |    override fun writeInsert(value: ${entity.simpleName}): List<DbValue> = listOf(
            |${writes(insert)}
            |    )
            |
            |    override fun writeUpdate(value: ${entity.simpleName}): List<DbValue> = listOf(
            |${writes(update)}
            |    )
            |
            |    override fun idOf(value: ${entity.simpleName}): DbValue =
            |        ${id.ormType}.encode(value.${id.property})
            |}
            |
        """.trimMargin()
    }

    private fun columnsText(entity: EntityModel): String {
        val properties = entity.fields.joinToString("\n\n") { field ->
            "val QueryScope<${entity.simpleName}>.${field.property}: Col<${field.kotlinType}>\n" +
                "    get() = col(\"${field.column}\", ${field.ormType})"
        }
        return """
            |// 由 keel-orm-ksp 生成，不要手改。
            |package ${entity.pkg}
            |
            |import moe.lolosia.keel.orm.*
            |
            |$properties
            |
        """.trimMargin()
    }

    private fun generateBootstrap(entities: List<EntityModel>, dialect: String) {
        val arguments = buildList {
            add(dialect)
            entities.forEach { add("${it.pkg}.${it.simpleName}Meta") }
        }.joinToString(",\n") { "            $it" }

        val text = """
            |// 由 keel-orm-ksp 生成，不要手改。
            |package $BOOTSTRAP_PACKAGE
            |
            |import moe.lolosia.keel.orm.*
            |
            |/** 插件 Worker 启动时登记方言与全部 @Table 实体；重复调用是幂等的。 */
            |object OrmMetaBootstrap {
            |    fun install() {
            |        OrmRuntime.install(
            |$arguments
            |        )
            |    }
            |}
            |
        """.trimMargin()

        val sources = entities.mapNotNull { it.declaration.containingFile }
        write(BOOTSTRAP_PACKAGE, "OrmMetaBootstrap", text, sources, aggregating = true)
    }

    private fun KSAnnotated.hasAnnotation(fqn: String): Boolean =
        annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == fqn }

    private fun KSDeclaration.stringArgument(fqn: String): String? =
        annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == fqn
        }?.arguments?.firstOrNull()?.value as? String

    private fun KSPropertyDeclaration.columnName(): String? {
        val annotation = annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == COLUMN_ANNOTATION
        } ?: return null
        val argument = annotation.arguments.firstOrNull { it.name?.asString() == "name" }
            ?: annotation.arguments.firstOrNull()
        return (argument?.value as? String)?.takeIf { it.isNotBlank() }
    }

    private fun snakeCase(name: String): String = buildString {
        name.forEachIndexed { index, char ->
            if (char.isUpperCase()) {
                val previous = name.getOrNull(index - 1)
                if (previous != null && (previous.isLowerCase() || previous.isDigit())) append('_')
                append(char.lowercaseChar())
            } else {
                append(char)
            }
        }
    }
}
