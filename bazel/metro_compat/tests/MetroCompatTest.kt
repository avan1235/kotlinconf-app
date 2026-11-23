package org.jetbrains.kotlinconf.bazel.metrocompat.tests

import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.kotlinconf.bazel.metrocompat.adaptMetroJar

class MetroCompatTest {
    @Test
    fun `adapts the Metro compiler jar with shims and redirects moved Kotlin 2_5_20-dev symbols`() {
        val metroJar = File(checkNotNull(System.getProperty("metro.jar")))
        val shimsJar = File(checkNotNull(System.getProperty("shims.jar")))
        val outJar = Files.createTempFile("metro-adapted-", ".jar").toFile()
        try {
            adaptMetroJar(metroJar, shimsJar, outJar)
            JarFile(outJar).use { jar ->
                val names = jar.entries().asSequence().map { it.name }.toSet()
                assertTrue("org/jetbrains/kotlinconf/bazel/metrocompat/shims/DiagnosticFactory0Shim.class" in names)
                assertTrue("org/jetbrains/kotlinconf/bazel/metrocompat/shims/DiagnosticFactory1Shim.class" in names)
                assertTrue("org/jetbrains/kotlinconf/bazel/metrocompat/shims/DiagnosticFactory2Shim.class" in names)
                assertTrue("org/jetbrains/kotlinconf/bazel/metrocompat/shims/ConeTypeParameterShim.class" in names)

                val diagnosticsBytes = jar
                    .getInputStream(jar.getJarEntry("dev/zacsweers/metro/compiler/fir/MetroDiagnostics.class"))
                    .use { it.readBytes() }
                    .decodeToString()
                assertFalse("org/jetbrains/kotlin/diagnostics/DiagnosticFactory2DelegateProvider" in diagnosticsBytes)
                assertTrue("org/jetbrains/kotlinconf/bazel/metrocompat/shims/DiagnosticFactory2Shim" in diagnosticsBytes)

                val irGenBytes = jar
                    .getInputStream(jar.getJarEntry("dev/zacsweers/metro/compiler/ir/graph/SyntheticGraphGenerator.class"))
                    .use { it.readBytes() }
                    .decodeToString()
                assertFalse("org/jetbrains/kotlin/ir/interpreter/UtilsKt" in irGenBytes)
                assertTrue("org/jetbrains/kotlin/ir/util/IrUtilsKt" in irGenBytes)

                val firReaderBytes = jar
                    .getInputStream(jar.getJarEntry("dev/zacsweers/metro/compiler/fir/FirTypeReader.class"))
                    .use { it.readBytes() }
                    .decodeToString()
                assertFalse("org/jetbrains/kotlin/fir/symbols/ConeTypeParameterLookupTag" in firReaderBytes)
                assertTrue("org/jetbrains/kotlinconf/bazel/metrocompat/shims/ConeTypeParameterShim" in firReaderBytes)
            }
        } finally {
            outJar.delete()
        }
    }
}
