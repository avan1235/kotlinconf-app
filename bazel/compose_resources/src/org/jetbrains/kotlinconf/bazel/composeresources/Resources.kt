package org.jetbrains.kotlinconf.bazel.composeresources

/** The resource types of the Compose Multiplatform resources library, as the Compose Gradle plugin names them. */
enum class ResourceType(val typeName: String, val accessorName: String, val className: String) {
    DRAWABLE("drawable", "drawable", "DrawableResource"),
    STRING("string", "string", "StringResource"),
    STRING_ARRAY("string-array", "array", "StringArrayResource"),
    PLURAL_STRING("plurals", "plurals", "PluralStringResource"),
    FONT("font", "font", "FontResource");

    /** The resources whose constructor takes the key of the value resource besides its id. */
    val requiresKeyName: Boolean
        get() = this == STRING || this == STRING_ARRAY || this == PLURAL_STRING

    /** The name of the generated accessor file of the type, e.g. `Drawable` or `StringArray`. */
    val fileName: String
        get() = typeName.split("-").joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

    override fun toString(): String = typeName

    companion object {
        fun fromString(str: String): ResourceType? = entries.firstOrNull { it.typeName.equals(str, true) }
    }
}

/**
 * One file (or one record of a `.cvr` file) that backs a resource: [path] is relative to the resources root
 * (`drawable-dark/icon.xml`, `values-de/strings.commonMain.cvr`), [offset] and [size] locate a record in a
 * `.cvr` file and are -1 for whole files.
 */
data class ResourceItem(
    val type: ResourceType,
    val qualifiers: List<String>,
    val name: String,
    val path: String,
    val contentHash: Int,
    val offset: Long = -1,
    val size: Long = -1,
)

/** A file of the prepared resource tree: its path relative to the `composeResources` root and its content. */
class PreparedFile(val path: String, val content: ByteArray)

class GenerationException(message: String) : RuntimeException(message)

internal fun fail(message: String): Nothing = throw GenerationException(message)

/** A resource name as a Kotlin identifier: `arrow-down` -> `arrow_down`, `1st` -> `_1st`. */
internal fun String.asUnderscoredIdentifier(): String =
    replace('-', '_').let { if (it.isNotEmpty() && it.first().isDigit()) "_$it" else it }

/**
 * Prepares the resource tree of a module as the Compose Gradle plugin does: the XML files of the `values*`
 * directories become `.cvr` files, `<name>.<sourceSet>.cvr`, and every other file is copied as is.
 *
 * @param inputs the files of the `composeResources` directory, by their path relative to it.
 */
fun prepareResources(inputs: Map<String, ByteArray>, sourceSet: String): List<PreparedFile> {
    val prepared = mutableListOf<PreparedFile>()
    for ((path, content) in inputs.toSortedMap()) {
        val segments = path.split("/")
        if (segments.size < 2) {
            fail("$path is not in a directory! Raw files should be placed in 'composeResources/files' directory.")
        }
        val dirName = segments.first()
        if (segments.size > 2 && dirName != "files") {
            fail("$path: nested directories are only supported in 'composeResources/files'.")
        }
        if (dirName.startsWith("values") && segments.size == 2 && segments[1].endsWith(".xml", ignoreCase = true)) {
            val cvr = try {
                convertXmlValueResource(content)
            } catch (e: GenerationException) {
                throw e
            } catch (e: Exception) {
                fail("XML file $path is not valid. ${e.message}")
            }
            val name = segments[1].substring(0, segments[1].length - ".xml".length)
            prepared.add(PreparedFile("$dirName/$name.$sourceSet.$CONVERTED_RESOURCE_EXT", cvr.encodeToByteArray()))
        } else {
            prepared.add(PreparedFile(path, content))
        }
    }
    return prepared
}

/**
 * The resources of a prepared tree, by type and by name: the drawable and font files, and the records of
 * the `.cvr` files; the `files` directory has no accessors.
 */
fun collectResourceItems(prepared: List<PreparedFile>): Map<ResourceType, Map<String, List<ResourceItem>>> =
    prepared.flatMap { it.toResourceItems() }
        .groupBy { it.type }
        .mapValues { (_, items) -> items.groupBy { it.name } }

private fun PreparedFile.toResourceItems(): List<ResourceItem> {
    val dirName = path.substringBefore('/')
    val fileName = path.substringAfterLast('/')
    val typeAndQualifiers = dirName.split("-")
    val typeString = typeAndQualifiers.first().lowercase()
    val qualifiers = typeAndQualifiers.drop(1)

    if (typeString == "string") {
        fail("Forbidden directory name '$dirName'! String resources should be declared in 'values/strings.xml'.")
    }
    if (typeString == "files") {
        if (qualifiers.isNotEmpty()) fail("The 'files' directory doesn't support qualifiers: '$dirName'.")
        return emptyList()
    }
    if (typeString == "values" && fileName.substringAfterLast('.', "").equals(CONVERTED_RESOURCE_EXT, true)) {
        return valueResourceItems(content.decodeToString(), qualifiers, path)
    }
    val type = ResourceType.fromString(typeString) ?: fail("Unknown resource type: '$typeString'.")
    return listOf(
        ResourceItem(
            type,
            qualifiers,
            fileName.substringBeforeLast('.').asUnderscoredIdentifier(),
            path,
            content.contentHashCode(),
        ),
    )
}

/** The records of a `.cvr` file, with the byte offset and size of each line (the first line is the format version). */
private fun valueResourceItems(cvr: String, qualifiers: List<String>, path: String): List<ResourceItem> {
    val result = mutableListOf<ResourceItem>()
    var offset = 0L
    for (line in cvr.lineSequence()) {
        if (line.isEmpty()) continue
        val size = line.encodeToByteArray().size
        if (offset > 0) {
            val record = ValueResourceRecord.createFromString(line)
            result.add(
                ResourceItem(
                    record.type,
                    qualifiers,
                    record.key.asUnderscoredIdentifier(),
                    path,
                    record.content.hashCode(),
                    offset,
                    size.toLong(),
                ),
            )
        }
        offset += size + 1 // "+1" for the newline character
    }
    return result
}
