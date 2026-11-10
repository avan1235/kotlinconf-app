package org.jetbrains.kotlinconf.bazel.composeresources

import java.util.Locale
import java.util.TreeMap

private const val RESOURCES_PACKAGE = "org.jetbrains.compose.resources"

// The accessors of a type are split into chunks, each with its own collector function, as the Compose Gradle
// plugin splits them into files: a single initializer or class of every accessor would exceed the JVM limits
// (MethodTooLargeException, ClassTooLargeException; https://github.com/JetBrains/compose-multiplatform/issues/4285).
private const val ITEMS_PER_CHUNK_LIMIT = 100

/** The declaration of the `Res` class and of the accessors: what the Compose Gradle plugin configures with `compose.resources`. */
class ResClass(
    val packageName: String,
    val className: String,
    val isPublic: Boolean,
) {
    /** The directory of the resources of the module in the final artifact, the `MD` constant of the accessors. */
    val moduleDir: String = "composeResources/$packageName/"
    val modifier: String = if (isPublic) "public" else "internal"
}

/** The `Res` object: `readBytes` and `getUri` of the files of the module, and the receivers of the accessors. */
fun resClassSource(res: ResClass): String = buildString {
    appendLine("@file:OptIn(InternalResourceApi::class)")
    appendLine("@file:Suppress(")
    appendLine("  \"RedundantVisibilityModifier\",")
    appendLine("  \"REDUNDANT_VISIBILITY_MODIFIER\",")
    appendLine(")")
    appendLine()
    appendLine("package ${res.packageName}")
    appendLine()
    appendLine("import kotlin.ByteArray")
    appendLine("import kotlin.OptIn")
    appendLine("import kotlin.String")
    appendLine("import kotlin.Suppress")
    appendLine("import $RESOURCES_PACKAGE.InternalResourceApi")
    appendLine("import $RESOURCES_PACKAGE.getResourceUri")
    appendLine("import $RESOURCES_PACKAGE.readResourceBytes")
    appendLine()
    appendLine("${res.modifier} object ${res.className} {")
    appendLine("  /**")
    appendLine("   * Reads the content of the resource file at the specified path and returns it as a byte array.")
    appendLine("   *")
    appendLine("   * Example: `val bytes = ${res.className}.readBytes(\"files/key.bin\")`")
    appendLine("   *")
    appendLine("   * @param path The path of the file to read in the compose resource's directory.")
    appendLine("   * @return The content of the file as a byte array.")
    appendLine("   */")
    appendLine("  public suspend fun readBytes(path: String): ByteArray = readResourceBytes(\"${res.moduleDir}\" + path)")
    appendLine()
    appendLine("  /**")
    appendLine("   * Returns the URI string of the resource file at the specified path.")
    appendLine("   *")
    appendLine("   * Example: `val uri = ${res.className}.getUri(\"files/key.bin\")`")
    appendLine("   *")
    appendLine("   * @param path The path of the file in the compose resource's directory.")
    appendLine("   * @return The URI string of the file.")
    appendLine("   */")
    appendLine("  public fun getUri(path: String): String = getResourceUri(\"${res.moduleDir}\" + path)")
    for (type in ResourceType.entries) {
        appendLine()
        appendLine("  public object ${type.accessorName}")
    }
    appendLine("}")
}

/** The resources of a type sorted by name, each with its items sorted by path, as the accessors are generated. */
fun sortResources(
    resources: Map<ResourceType, Map<String, List<ResourceItem>>>,
): TreeMap<ResourceType, TreeMap<String, List<ResourceItem>>> {
    val result = TreeMap<ResourceType, TreeMap<String, List<ResourceItem>>>()
    resources.forEach { (type, items) ->
        val typeResult = TreeMap<String, List<ResourceItem>>()
        items.forEach { (name, resItems) -> typeResult[name] = resItems.sortedBy { it.path } }
        result[type] = typeResult
    }
    return result
}

/** The names of the collector functions of the accessors of [type], `_collect<SourceSet><Type><chunk>Resources`. */
fun collectorFunctions(type: ResourceType, resourceCount: Int, sourceSet: String): List<String> =
    List((resourceCount + ITEMS_PER_CHUNK_LIMIT - 1) / ITEMS_PER_CHUNK_LIMIT) { index ->
        "_collect${sourceSet.uppercaseFirstChar()}${type.accessorName.uppercaseFirstChar()}${index}Resources"
    }

/**
 * The accessors of the resources of one type, `Res.drawable.icon`, with a collector function per chunk of
 * them; a file without resources only declares the package.
 */
fun accessorsSource(
    type: ResourceType,
    idToResources: TreeMap<String, List<ResourceItem>>,
    res: ResClass,
    sourceSet: String,
): String {
    if (idToResources.isEmpty()) {
        return "// No ${type.typeName} resources in this source set.\n\npackage ${res.packageName}\n"
    }
    val imports = sortedSetOf(
        "kotlin.OptIn",
        "kotlin.String",
        "kotlin.collections.MutableMap",
        "$RESOURCES_PACKAGE.${type.className}",
        "$RESOURCES_PACKAGE.InternalResourceApi",
        "$RESOURCES_PACKAGE.ResourceContentHash",
        "$RESOURCES_PACKAGE.ResourceItem",
    )
    val body = StringBuilder()
    val collectors = collectorFunctions(type, idToResources.size, sourceSet)
    idToResources.keys.chunked(ITEMS_PER_CHUNK_LIMIT).forEachIndexed { index, names ->
        if (index > 0) body.appendLine()
        for (name in names) {
            val items = idToResources.getValue(name)
            val accessor = name.escapeIfNecessary()
            val hash = items.fold(0) { acc, item -> acc * 31 + item.contentHash }
            body.appendLine("@delegate:ResourceContentHash(${hash.toKotlinLiteral()})")
            body.appendLine("${res.modifier} val ${res.className}.${type.accessorName}.$accessor: ${type.className} by lazy {")
            if (type.requiresKeyName) {
                body.appendLine("      ${type.className}(\"$type:$name\", \"$name\", setOf(")
            } else {
                body.appendLine("      ${type.className}(\"$type:$name\", setOf(")
            }
            for (item in items) {
                body.appendLine("        ResourceItem(setOf(${item.qualifiersCode(imports)}), \"\${MD}${item.path}\", ${item.offset}, ${item.size}),")
            }
            body.appendLine("      ))")
            body.appendLine("    }")
            body.appendLine()
        }
        body.appendLine("@InternalResourceApi")
        body.appendLine("internal fun ${collectors[index]}(map: MutableMap<String, ${type.className}>) {")
        for (name in names) {
            body.appendLine("  map.put(\"$name\", ${res.className}.${type.accessorName}.${name.escapeIfNecessary()})")
        }
        body.appendLine("}")
    }
    return buildString {
        appendLine("@file:OptIn(InternalResourceApi::class)")
        appendLine()
        appendLine("package ${res.packageName}")
        appendLine()
        imports.forEach { appendLine("import $it") }
        appendLine()
        appendLine("private const val MD: String = \"${res.moduleDir}\"")
        appendLine()
        append(body)
    }
}

/** The `expect` collectors of the common source set: `Res.allDrawableResources` and the other types. */
fun expectCollectorsSource(res: ResClass): String = buildString {
    appendLine("package ${res.packageName}")
    appendLine()
    appendLine("import kotlin.String")
    appendLine("import kotlin.collections.Map")
    ResourceType.entries.map { it.className }.sorted().forEach { appendLine("import $RESOURCES_PACKAGE.$it") }
    for (type in ResourceType.entries) {
        appendLine()
        appendLine("${res.modifier} expect val ${res.className}.all${type.className}s: Map<String, ${type.className}>")
    }
}

/**
 * The collectors of a platform: the map of all resources of a type, filled by the collector functions of the
 * accessors; `actual` declarations in a multiplatform module, plain ones in a single-platform (JVM) one.
 */
fun actualCollectorsSource(
    res: ResClass,
    typeToCollectorFunctions: Map<ResourceType, List<String>>,
    useActualModifier: Boolean,
): String = buildString {
    appendLine("@file:OptIn($RESOURCES_PACKAGE.InternalResourceApi::class)")
    appendLine()
    appendLine("package ${res.packageName}")
    appendLine()
    appendLine("import kotlin.OptIn")
    appendLine("import kotlin.String")
    appendLine("import kotlin.collections.Map")
    ResourceType.entries.map { it.className }.sorted().forEach { appendLine("import $RESOURCES_PACKAGE.$it") }
    val modifiers = if (useActualModifier) "${res.modifier} actual" else res.modifier
    for (type in ResourceType.entries) {
        appendLine()
        appendLine("$modifiers val ${res.className}.all${type.className}s: Map<String, ${type.className}> by lazy {")
        appendLine("  val map = mutableMapOf<String, ${type.className}>()")
        typeToCollectorFunctions[type].orEmpty().sorted().forEach { appendLine("  $it(map)") }
        appendLine("  return@lazy map")
        appendLine("}")
    }
}

/** The qualifiers of an item as the arguments of `setOf(...)`, e.g. `ThemeQualifier.DARK, LanguageQualifier("de"), `. */
private fun ResourceItem.qualifiersCode(imports: MutableSet<String>): String {
    val languageRegex = Regex("[a-z]{2,3}")
    val regionRegex = Regex("r[A-Z]{2}")
    val qualifiersMap = mutableMapOf<String, String>()

    fun saveQualifier(className: String, qualifier: String) {
        qualifiersMap[className]?.let { fail("$path contains repetitive qualifiers: '$it' and '$qualifier'.") }
        qualifiersMap[className] = qualifier
    }

    for (q in qualifiers) {
        when (q) {
            "light", "dark" -> saveQualifier("ThemeQualifier", q)
            "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "ldpi" -> saveQualifier("DensityQualifier", q)
            else -> when {
                q.matches(languageRegex) -> saveQualifier("LanguageQualifier", q)
                q.matches(regionRegex) -> saveQualifier("RegionQualifier", q)
                else -> fail("$path contains unknown qualifier: '$q'.")
            }
        }
    }
    imports.addAll(qualifiersMap.keys.map { "$RESOURCES_PACKAGE.$it" })

    return buildString {
        qualifiersMap["ThemeQualifier"]?.let { append("ThemeQualifier.${it.uppercase()}, ") }
        qualifiersMap["DensityQualifier"]?.let { append("DensityQualifier.${it.uppercase()}, ") }
        qualifiersMap["LanguageQualifier"]?.let { append("LanguageQualifier(\"$it\"), ") }
        qualifiersMap["RegionQualifier"]?.let { q ->
            val lang = qualifiersMap["LanguageQualifier"]
                ?: fail("Region qualifier must be used only with language.\nFile: $path")
            val langAndRegion = "$lang-$q"
            if (!path.contains("-$langAndRegion")) {
                fail("Region qualifier must be declared after language: '$langAndRegion'.\nFile: $path")
            }
            append("RegionQualifier(\"${q.takeLast(2)}\"), ")
        }
    }
}

private val KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
    "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof",
    "val", "var", "when", "while",
)

private val IDENTIFIER = Regex("[\\p{L}_][\\p{L}\\p{N}_]*")

/** A resource name as a Kotlin name: in backticks when it is a keyword or not an identifier. */
private fun String.escapeIfNecessary(): String =
    if (this in KEYWORDS || !matches(IDENTIFIER)) "`$this`" else this

/** An Int literal with the grouping of the generated code, `1_013_812_100`. */
private fun Int.toKotlinLiteral(): String = String.format(Locale.ROOT, "%,d", this).replace(',', '_')

private fun String.uppercaseFirstChar(): String = replaceFirstChar { it.uppercaseChar() }
