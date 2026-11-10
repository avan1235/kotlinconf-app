package org.jetbrains.kotlinconf.bazel.composeresources

import java.io.ByteArrayInputStream
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Node

/** The extension of a converted value resource file ("Compose Value Resource"). */
const val CONVERTED_RESOURCE_EXT = "cvr"

private const val FORMAT_VERSION = 0

/** A line of a `.cvr` file: `type|key|content`, the content being base64 (comma-separated for arrays and plurals). */
data class ValueResourceRecord(
    val type: ResourceType,
    val key: String,
    val content: String,
) {
    fun getAsString(): String = listOf(type.typeName, key, content).joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = "|"

        fun createFromString(string: String): ValueResourceRecord {
            val parts = string.split(SEPARATOR)
            return ValueResourceRecord(
                ResourceType.fromString(parts[0]) ?: fail("Unknown resource type: '${parts[0]}'."),
                parts[1],
                parts[2],
            )
        }
    }
}

/**
 * Converts an XML file of a `values` directory to the `.cvr` format the resources library reads: the format
 * version, then one sorted line per `string`, `string-array` and `plurals` element.
 */
fun convertXmlValueResource(xml: ByteArray): String {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    val resources = doc.getElementsByTagName("resources").item(0) ?: fail("The root element must be <resources>.")
    val items = resources.childNodes
    val records = List(items.length) { items.item(it) }
        .filter { it.hasAttributes() }
        .map { getItemRecord(it) }

    // No duplicates of type + key.
    records.groupBy { it.key }
        .filter { it.value.size > 1 }
        .forEach { (key, duplicates) ->
            val allTypes = duplicates.map { it.type }
            if (allTypes.size != allTypes.toSet().size) fail("Duplicated key '$key'.")
        }

    return buildString {
        appendLine("version:$FORMAT_VERSION")
        records.map { it.getAsString() }.sorted().forEach { appendLine(it) }
    }
}

private fun getItemRecord(node: Node): ValueResourceRecord {
    val type = ResourceType.fromString(node.nodeName) ?: fail("Unknown resource type: '${node.nodeName}'.")
    val key = node.attributes.getNamedItem("name")?.nodeValue ?: fail("Attribute 'name' not found.")
    val value = when (type) {
        ResourceType.STRING -> handleSpecialCharacters(node.textContent).asBase64()

        ResourceType.STRING_ARRAY -> node.items().joinToString(",") { child ->
            handleSpecialCharacters(child.textContent).asBase64()
        }

        ResourceType.PLURAL_STRING -> node.items().joinToString(",") { child ->
            val content = handleSpecialCharacters(child.textContent)
            val quantity = child.attributes.getNamedItem("quantity")?.nodeValue
                ?: fail("Attribute 'quantity' not found in the plurals '$key'.")
            quantity.uppercase() + ":" + content.asBase64()
        }

        else -> fail("Unknown string resource type: '$type'.")
    }
    return ValueResourceRecord(type, key, value)
}

private fun Node.items(): List<Node> = List(childNodes.length) { childNodes.item(it) }.filter { it.nodeName == "item" }

private fun String.asBase64(): String = Base64.getEncoder().encode(encodeToByteArray()).decodeToString()

/**
 * Replaces the escapes of Android string resources: `\n` (new line), `\t` (tab), `\uXXXX` (a unicode symbol)
 * and `\\` (a backslash).
 */
fun handleSpecialCharacters(string: String): String {
    val unicodeNewLineTabRegex = Regex("""\\u[a-fA-F\d]{4}|\\n|\\t""")
    val doubleSlashRegex = Regex("""\\\\""")
    val doubleSlashIndexes = doubleSlashRegex.findAll(string).map { it.range.first }
    return unicodeNewLineTabRegex.replace(string) { matchResult ->
        if (doubleSlashIndexes.contains(matchResult.range.first - 1)) {
            matchResult.value
        } else {
            when (matchResult.value) {
                "\\n" -> "\n"
                "\\t" -> "\t"
                else -> matchResult.value.substring(2).toInt(16).toChar().toString()
            }
        }
    }.replace("""\\""", """\""")
}
