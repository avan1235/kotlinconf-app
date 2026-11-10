package org.jetbrains.kotlinconf.bazel.composeresources.tests

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.example.fixture.generated.resources.Res
import org.example.fixture.generated.resources.allDrawableResources
import org.example.fixture.generated.resources.allFontResources
import org.example.fixture.generated.resources.allPluralStringResources
import org.example.fixture.generated.resources.allStringArrayResources
import org.example.fixture.generated.resources.allStringResources
import org.example.fixture.generated.resources.app_name
import org.example.fixture.generated.resources.arrow_down
import org.example.fixture.generated.resources.days
import org.example.fixture.generated.resources.greeting
import org.example.fixture.generated.resources.icon
import org.example.fixture.generated.resources.items
import org.example.fixture.generated.resources.`object`
import org.example.fixture.single.SingleRes
import org.example.fixture.single.allStringResources as allSingleStringResources
import org.example.fixture.single.greeting as singleGreeting
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.MissingResourceException
import org.jetbrains.compose.resources.getDrawableResourceBytes
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import org.jetbrains.compose.resources.getSystemResourceEnvironment

/**
 * The resources of the fixtures (see BUILD.bazel) resolve through the Compose resources runtime from the
 * JVM packaging of `compose_resources`: the jar of the prepared tree on the classpath of the library.
 */
@OptIn(ExperimentalResourceApi::class)
class LookupTest {
    /** Runs [block] with [locale] as the default one, which the runtime reads for the language qualifier. */
    private fun withLocale(locale: Locale, block: suspend () -> Unit) {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            runBlocking { block() }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `the strings of the default values and of the language of the locale`() = withLocale(Locale.ENGLISH) {
        assertEquals("Fixture", getString(Res.string.app_name))
        assertEquals("Hello,\nworld & friends", getString(Res.string.greeting))
        assertEquals("a keyword as a name", getString(Res.string.`object`))
        assertEquals(listOf("Mon", "Tue"), getStringArray(Res.array.days))
        assertEquals("1 item", getPluralString(Res.plurals.items, 1, 1))
        assertEquals("5 items", getPluralString(Res.plurals.items, 5, 5))
    }

    @Test
    fun `a translated string, and the default one when the language has no translation`() = withLocale(Locale.GERMAN) {
        assertEquals("Hallo Welt", getString(Res.string.greeting))
        assertEquals("Fixture", getString(Res.string.app_name))
    }

    @Test
    fun `the drawables, the one of the theme of the system when it has variants`() = withLocale(Locale.ENGLISH) {
        val environment = getSystemResourceEnvironment()
        val arrow = getDrawableResourceBytes(environment, Res.drawable.arrow_down).decodeToString()
        assertTrue(arrow.contains("android:name=\"arrow_down\""), arrow)
        val icon = getDrawableResourceBytes(environment, Res.drawable.icon).decodeToString()
        assertTrue(icon.contains("android:name=\"icon_light\"") || icon.contains("android:name=\"icon_dark\""), icon)
    }

    @Test
    fun `the raw files by path, with their URI in the jar`() = withLocale(Locale.ENGLISH) {
        assertEquals("{\"fixture\": true}\n", Res.readBytes("files/data.json").decodeToString())
        assertEquals("a raw file in a nested directory\n", Res.readBytes("files/nested/note.txt").decodeToString())
        val uri = Res.getUri("files/data.json")
        assertTrue(uri.startsWith("jar:file:"), uri)
        assertTrue(uri.endsWith("!/composeResources/org.example.fixture.generated.resources/files/data.json"), uri)
        assertFailsWith<MissingResourceException> { Res.readBytes("files/missing.json") }
    }

    @Test
    fun `the collectors list every resource of a type`() {
        assertEquals(setOf("app_name", "greeting", "object"), Res.allStringResources.keys)
        assertEquals(setOf("arrow_down", "icon"), Res.allDrawableResources.keys)
        assertEquals(setOf("days"), Res.allStringArrayResources.keys)
        assertEquals(setOf("items"), Res.allPluralStringResources.keys)
        assertEquals(setOf("body"), Res.allFontResources.keys)
    }

    @Test
    fun `the resources of a JVM-only module, with plain collectors and a public Res class`() = withLocale(Locale.GERMAN) {
        assertEquals("Hallo Welt", getString(SingleRes.string.singleGreeting))
        assertEquals(setOf("app_name", "greeting", "object"), SingleRes.allSingleStringResources.keys)
        assertEquals("{\"fixture\": true}\n", SingleRes.readBytes("files/data.json").decodeToString())
        assertTrue(SingleRes.getUri("files/data.json").endsWith("!/composeResources/org.example.fixture.single/files/data.json"))
    }
}
