package org.jetbrains.kotlinconf.bazel.composeresources.tests

import java.io.File
import java.util.Base64
import java.util.Locale
import java.util.TreeMap
import java.util.jar.JarFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.kotlinconf.bazel.composeresources.Options
import org.jetbrains.kotlinconf.bazel.composeresources.ResClass
import org.jetbrains.kotlinconf.bazel.composeresources.ResourceType
import org.jetbrains.kotlinconf.bazel.composeresources.ValueResourceRecord
import org.jetbrains.kotlinconf.bazel.composeresources.accessorsSource
import org.jetbrains.kotlinconf.bazel.composeresources.collectResourceItems
import org.jetbrains.kotlinconf.bazel.composeresources.generate
import org.jetbrains.kotlinconf.bazel.composeresources.handleSpecialCharacters
import org.jetbrains.kotlinconf.bazel.composeresources.prepareResources
import org.jetbrains.kotlinconf.bazel.composeresources.sortResources

/** The generator on the fixture of testdata/composeResources (see BUILD.bazel). */
class GeneratorTest {
    private val fixtureDir = File(System.getenv("TEST_SRCDIR"), System.getenv("TEST_WORKSPACE"))
        .resolve("bazel/compose_resources/tests/testdata/composeResources")

    private val inputs: Map<String, File> = fixtureDir.walkTopDown()
        .filter { it.isFile }
        .associateBy { it.relativeTo(fixtureDir).invariantSeparatorsPath }

    private val prepared = prepareResources(inputs.mapValues { (_, file) -> file.readBytes() }, "commonMain")

    private fun preparedContent(path: String): ByteArray = prepared.single { it.path == path }.content

    private fun b64(value: String): String = Base64.getEncoder().encodeToString(value.encodeToByteArray())

    @Test
    fun `the values become cvr files of the source set and the other files are copied as they are`() {
        assertEquals(
            setOf(
                "drawable/arrow-down.xml",
                "drawable/icon.xml",
                "drawable-dark/icon.xml",
                "files/data.json",
                "files/nested/note.txt",
                "font/body.ttf",
                "values/strings.commonMain.cvr",
                "values-de/strings.commonMain.cvr",
            ),
            prepared.map { it.path }.toSet(),
        )
        assertContentEquals(inputs.getValue("font/body.ttf").readBytes(), preparedContent("font/body.ttf"))
        assertContentEquals(inputs.getValue("files/nested/note.txt").readBytes(), preparedContent("files/nested/note.txt"))
    }

    @Test
    fun `a cvr file is the format version and one sorted base64 record per value`() {
        val lines = preparedContent("values/strings.commonMain.cvr").decodeToString().lines().filter { it.isNotEmpty() }
        assertEquals("version:0", lines.first())
        assertEquals(
            listOf(
                "plurals|items|ONE:${b64("%1\$d item")},OTHER:${b64("%1\$d items")}",
                "string-array|days|${b64("Mon")},${b64("Tue")}",
                "string|app_name|${b64("Fixture")}",
                "string|greeting|${b64("Hello,\nworld & friends")}",
                "string|object|${b64("a keyword as a name")}",
            ),
            lines.drop(1),
        )
        assertEquals(
            listOf("version:0", "string|greeting|${b64("Hallo Welt")}"),
            preparedContent("values-de/strings.commonMain.cvr").decodeToString().lines().filter { it.isNotEmpty() },
        )
    }

    @Test
    fun `the escapes of the values are those of Android string resources`() {
        assertEquals("a\nb\tc A \\n \\", handleSpecialCharacters("""a\nb\tc \u0041 \\n \\"""))
    }

    @Test
    fun `the items address their record of a cvr file by byte offset, and a file as a whole`() {
        val items = collectResourceItems(prepared)
        assertEquals(
            setOf(ResourceType.DRAWABLE, ResourceType.STRING, ResourceType.STRING_ARRAY, ResourceType.PLURAL_STRING, ResourceType.FONT),
            items.keys,
        )
        assertEquals(setOf("arrow_down", "icon"), items.getValue(ResourceType.DRAWABLE).keys)
        assertEquals(setOf("app_name", "greeting", "object"), items.getValue(ResourceType.STRING).keys)
        assertEquals(setOf("days"), items.getValue(ResourceType.STRING_ARRAY).keys)
        assertEquals(setOf("items"), items.getValue(ResourceType.PLURAL_STRING).keys)
        assertEquals(setOf("body"), items.getValue(ResourceType.FONT).keys)

        val greeting = items.getValue(ResourceType.STRING).getValue("greeting").sortedBy { it.path }
        assertEquals(listOf("values-de/strings.commonMain.cvr", "values/strings.commonMain.cvr"), greeting.map { it.path })
        assertEquals(listOf(listOf("de"), emptyList()), greeting.map { it.qualifiers })
        for (item in greeting) {
            val cvr = preparedContent(item.path)
            val line = cvr.copyOfRange(item.offset.toInt(), (item.offset + item.size).toInt()).decodeToString()
            assertTrue(line.startsWith("string|greeting|"), "the record at ${item.offset} of ${item.path}: $line")
            assertEquals('\n', cvr[(item.offset + item.size).toInt()].toInt().toChar())
            assertEquals(ValueResourceRecord.createFromString(line).content.hashCode(), item.contentHash)
        }

        val icon = items.getValue(ResourceType.DRAWABLE).getValue("icon")
        assertEquals(setOf("drawable-dark/icon.xml", "drawable/icon.xml"), icon.map { it.path }.toSet())
        assertTrue(icon.all { it.offset == -1L && it.size == -1L })
        assertEquals(listOf("dark"), icon.single { it.path == "drawable-dark/icon.xml" }.qualifiers)
    }

    @Test
    fun `the accessors declare the items with their qualifiers, content hash and escaped names`() {
        val res = ResClass("org.example.fixture.generated.resources", "Res", false)
        val resources = sortResources(collectResourceItems(prepared))

        val drawables = accessorsSource(ResourceType.DRAWABLE, resources.getValue(ResourceType.DRAWABLE), res, "commonMain")
        assertContains(drawables, "package org.example.fixture.generated.resources\n")
        assertContains(drawables, "import org.jetbrains.compose.resources.ThemeQualifier\n")
        assertContains(drawables, "private const val MD: String = \"composeResources/org.example.fixture.generated.resources/\"\n")
        assertContains(drawables, "internal val Res.drawable.arrow_down: DrawableResource by lazy {\n")
        assertContains(drawables, "      DrawableResource(\"drawable:arrow_down\", setOf(\n")
        assertContains(drawables, "        ResourceItem(setOf(ThemeQualifier.DARK, ), \"\${MD}drawable-dark/icon.xml\", -1, -1),\n")
        assertContains(drawables, "        ResourceItem(setOf(), \"\${MD}drawable/icon.xml\", -1, -1),\n")
        assertContains(drawables, "internal fun _collectCommonMainDrawable0Resources(map: MutableMap<String, DrawableResource>) {\n")
        assertContains(drawables, "  map.put(\"arrow_down\", Res.drawable.arrow_down)\n")
        // The hash of a resource folds the hashes of its items, sorted by path, as the Gradle plugin does.
        val dark = inputs.getValue("drawable-dark/icon.xml").readBytes().contentHashCode()
        val light = inputs.getValue("drawable/icon.xml").readBytes().contentHashCode()
        val iconHash = String.format(Locale.ROOT, "%,d", dark * 31 + light).replace(',', '_')
        assertContains(drawables, "@delegate:ResourceContentHash($iconHash)\ninternal val Res.drawable.icon: DrawableResource by lazy {\n")

        val strings = accessorsSource(ResourceType.STRING, resources.getValue(ResourceType.STRING), res, "commonMain")
        assertContains(strings, "import org.jetbrains.compose.resources.LanguageQualifier\n")
        assertContains(strings, "internal val Res.string.`object`: StringResource by lazy {\n")
        assertContains(strings, "      StringResource(\"string:object\", \"object\", setOf(\n")
        assertContains(strings, "        ResourceItem(setOf(LanguageQualifier(\"de\"), ), \"\${MD}values-de/strings.commonMain.cvr\", ")
        assertContains(strings, "  map.put(\"object\", Res.string.`object`)\n")

        val publicRes = ResClass("org.example.fixture.single", "SingleRes", true)
        val fonts = accessorsSource(ResourceType.FONT, resources.getValue(ResourceType.FONT), publicRes, "main")
        assertContains(fonts, "public val SingleRes.font.body: FontResource by lazy {\n")
        assertContains(fonts, "internal fun _collectMainFont0Resources(map: MutableMap<String, FontResource>) {\n")

        assertEquals(
            "// No font resources in this source set.\n\npackage org.example.fixture.generated.resources\n",
            accessorsSource(ResourceType.FONT, TreeMap(), res, "commonMain"),
        )
    }

    @Test
    fun `a multiplatform module gets expect and actual collectors, a JVM-only module plain ones`() {
        val out = createTempDirectory(File(System.getenv("TEST_TMPDIR")).toPath(), "generated").toFile()

        val multi = out.resolve("multi")
        generate(
            Options(
                res = ResClass("org.example.multi", "Res", false),
                sourceSet = "commonMain",
                multiplatform = true,
                inputs = inputs,
                kotlinDir = multi.resolve("common"),
                platformKotlinDir = multi.resolve("platform"),
                resourcesDir = multi.resolve("resources"),
                jar = multi.resolve("resources.jar"),
            ),
        )
        assertEquals(
            setOf(
                "Res.kt",
                "Drawable.commonMain.kt",
                "String.commonMain.kt",
                "StringArray.commonMain.kt",
                "Plurals.commonMain.kt",
                "Font.commonMain.kt",
                "ExpectResourceCollectors.kt",
            ),
            multi.resolve("common").list()!!.toSet(),
        )
        assertEquals(setOf("ActualResourceCollectors.kt"), multi.resolve("platform").list()!!.toSet())
        assertContains(multi.resolve("common/Res.kt").readText(), "internal object Res {\n")
        assertContains(
            multi.resolve("common/Res.kt").readText(),
            "readResourceBytes(\"composeResources/org.example.multi/\" + path)",
        )
        assertContains(
            multi.resolve("common/ExpectResourceCollectors.kt").readText(),
            "internal expect val Res.allStringResources: Map<String, StringResource>\n",
        )
        val actual = multi.resolve("platform/ActualResourceCollectors.kt").readText()
        assertContains(actual, "internal actual val Res.allDrawableResources: Map<String, DrawableResource> by lazy {\n")
        assertContains(actual, "  _collectCommonMainDrawable0Resources(map)\n")
        assertTrue(multi.resolve("resources/composeResources/org.example.multi/values-de/strings.commonMain.cvr").isFile)
        assertTrue(multi.resolve("resources/composeResources/org.example.multi/files/nested/note.txt").isFile)
        JarFile(multi.resolve("resources.jar")).use { jar ->
            val entries = jar.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toSet()
            assertEquals(prepared.map { "composeResources/org.example.multi/" + it.path }.toSet(), entries)
            val cvr = jar.getInputStream(jar.getEntry("composeResources/org.example.multi/values/strings.commonMain.cvr")).readBytes()
            assertContentEquals(preparedContent("values/strings.commonMain.cvr"), cvr)
        }

        val single = out.resolve("single")
        generate(
            Options(
                res = ResClass("org.example.single", "SingleRes", true),
                sourceSet = "main",
                multiplatform = false,
                inputs = inputs,
                kotlinDir = single.resolve("kotlin"),
                platformKotlinDir = null,
                resourcesDir = null,
                jar = null,
            ),
        )
        assertEquals(
            setOf(
                "SingleRes.kt",
                "Drawable.main.kt",
                "String.main.kt",
                "StringArray.main.kt",
                "Plurals.main.kt",
                "Font.main.kt",
                "ResourceCollectors.kt",
            ),
            single.resolve("kotlin").list()!!.toSet(),
        )
        assertContains(single.resolve("kotlin/SingleRes.kt").readText(), "public object SingleRes {\n")
        val collectors = single.resolve("kotlin/ResourceCollectors.kt").readText()
        assertContains(collectors, "public val SingleRes.allStringResources: Map<String, StringResource> by lazy {\n")
        assertContains(collectors, "  _collectMainString0Resources(map)\n")
        assertFalse(collectors.contains("actual"), collectors)
        assertFalse(single.resolve("resources").exists())
    }
}
