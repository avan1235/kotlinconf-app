package org.jetbrains.kotlinconf.bazel.composeresources

import java.io.File
import java.time.LocalDateTime
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/**
 * The options of a generation, the flags of the command line (see [parse]); the `compose_resources` rule
 * of //bazel:compose_resources.bzl passes them.
 */
class Options(
    val res: ResClass,
    val sourceSet: String,
    val multiplatform: Boolean,
    /** The input files by their path relative to the `composeResources` directory. */
    val inputs: Map<String, File>,
    /** The directory of the generated sources of the common (or only) source set. */
    val kotlinDir: File,
    /** The directory of the `actual` collectors of the platform source sets, in a multiplatform module. */
    val platformKotlinDir: File?,
    /** The directory of the prepared resource tree, `composeResources/<package>/...`. */
    val resourcesDir: File?,
    /** The jar of the prepared resource tree, for the JVM classpath. */
    val jar: File?,
) {
    companion object {
        const val USAGE = """usage: generator --package <package> [--res-class <name>] [--public] [--source-set <name>]
    [--multiplatform] --input <path relative to composeResources>=<file>... --kotlin-dir <dir>
    [--platform-kotlin-dir <dir>] [--resources-dir <dir>] [--jar <file>]
  Arguments starting with @ name a file with one argument per line."""

        fun parse(args: List<String>): Options {
            var packageName: String? = null
            var className = "Res"
            var isPublic = false
            var sourceSet = "commonMain"
            var multiplatform = false
            val inputs = linkedMapOf<String, File>()
            var kotlinDir: File? = null
            var platformKotlinDir: File? = null
            var resourcesDir: File? = null
            var jar: File? = null

            val iterator = args.iterator()
            fun value(flag: String): String {
                require(iterator.hasNext()) { "$flag needs a value" }
                return iterator.next()
            }
            while (iterator.hasNext()) {
                when (val flag = iterator.next()) {
                    "--package" -> packageName = value(flag)
                    "--res-class" -> className = value(flag)
                    "--public" -> isPublic = true
                    "--source-set" -> sourceSet = value(flag)
                    "--multiplatform" -> multiplatform = true
                    "--input" -> {
                        val input = value(flag)
                        val separator = input.indexOf('=')
                        require(separator > 0) { "--input must be <relative path>=<file>, not '$input'" }
                        val path = input.substring(0, separator)
                        require(inputs.put(path, File(input.substring(separator + 1))) == null) { "duplicate input '$path'" }
                    }
                    "--kotlin-dir" -> kotlinDir = File(value(flag))
                    "--platform-kotlin-dir" -> platformKotlinDir = File(value(flag))
                    "--resources-dir" -> resourcesDir = File(value(flag))
                    "--jar" -> jar = File(value(flag))
                    else -> throw IllegalArgumentException("unknown argument '$flag'")
                }
            }
            require(!packageName.isNullOrEmpty()) { "--package is required" }
            require(kotlinDir != null) { "--kotlin-dir is required" }
            require(multiplatform || platformKotlinDir == null) { "--platform-kotlin-dir needs --multiplatform" }
            return Options(
                ResClass(packageName, className, isPublic),
                sourceSet,
                multiplatform,
                inputs,
                kotlinDir,
                platformKotlinDir,
                resourcesDir,
                jar,
            )
        }
    }
}

/** The names of the generated source files, the outputs the `compose_resources` rule declares. */
object GeneratedFiles {
    const val EXPECT_COLLECTORS = "ExpectResourceCollectors.kt"
    const val ACTUAL_COLLECTORS = "ActualResourceCollectors.kt"
    const val COLLECTORS = "ResourceCollectors.kt"

    fun resClass(res: ResClass): String = "${res.className}.kt"

    fun accessors(type: ResourceType, sourceSet: String): String = "${type.fileName}.$sourceSet.kt"
}

fun main(args: Array<String>) {
    val options = try {
        Options.parse(expandArgFiles(args.toList()))
    } catch (e: IllegalArgumentException) {
        System.err.println("compose_resources generator: ${e.message}\n${Options.USAGE}")
        exitProcess(2)
    }
    try {
        generate(options)
    } catch (e: GenerationException) {
        System.err.println("compose_resources generator: ${e.message}")
        exitProcess(1)
    }
}

/** Replaces each `@file` argument by the lines of the file (the params file of the Bazel action). */
fun expandArgFiles(args: List<String>): List<String> = args.flatMap { arg ->
    if (arg.startsWith("@")) File(arg.substring(1)).readLines().filter { it.isNotEmpty() } else listOf(arg)
}

/** Generates the sources and packages the resources of [options]. */
fun generate(options: Options) {
    val inputs = options.inputs.mapValues { (_, file) -> file.readBytes() }
    val prepared = prepareResources(inputs, options.sourceSet)
    val resources = sortResources(collectResourceItems(prepared))
    val res = options.res
    val sourceSet = options.sourceSet

    val collectors = ResourceType.entries.associateWith { type ->
        collectorFunctions(type, resources[type]?.size ?: 0, sourceSet)
    }
    options.kotlinDir.mkdirs()
    options.kotlinDir.resolve(GeneratedFiles.resClass(res)).writeText(resClassSource(res))
    for (type in ResourceType.entries) {
        options.kotlinDir.resolve(GeneratedFiles.accessors(type, sourceSet))
            .writeText(accessorsSource(type, resources[type] ?: TreeMap(), res, sourceSet))
    }
    if (options.multiplatform) {
        options.kotlinDir.resolve(GeneratedFiles.EXPECT_COLLECTORS).writeText(expectCollectorsSource(res))
        options.platformKotlinDir?.let { dir ->
            dir.mkdirs()
            dir.resolve(GeneratedFiles.ACTUAL_COLLECTORS).writeText(actualCollectorsSource(res, collectors, true))
        }
    } else {
        options.kotlinDir.resolve(GeneratedFiles.COLLECTORS).writeText(actualCollectorsSource(res, collectors, false))
    }

    options.resourcesDir?.let { dir ->
        for (file in prepared) {
            val target = dir.resolve(res.moduleDir).resolve(file.path)
            target.parentFile.mkdirs()
            target.writeBytes(file.content)
        }
    }
    options.jar?.let { writeJar(it, prepared, res.moduleDir) }
}

// The entries of the jar get a fixed time, for a reproducible output.
private val JAR_ENTRY_TIME: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0)

private fun writeJar(jar: File, prepared: List<PreparedFile>, moduleDir: String) {
    jar.parentFile?.mkdirs()
    ZipOutputStream(jar.outputStream().buffered()).use { zip ->
        val directories = mutableSetOf<String>()
        for (file in prepared.sortedBy { it.path }) {
            val entryPath = moduleDir + file.path
            var slash = entryPath.indexOf('/')
            while (slash >= 0) {
                val directory = entryPath.substring(0, slash + 1)
                if (directories.add(directory)) {
                    zip.putNextEntry(ZipEntry(directory).apply { setTimeLocal(JAR_ENTRY_TIME) })
                    zip.closeEntry()
                }
                slash = entryPath.indexOf('/', slash + 1)
            }
            zip.putNextEntry(ZipEntry(entryPath).apply { setTimeLocal(JAR_ENTRY_TIME) })
            zip.write(file.content)
            zip.closeEntry()
        }
    }
}
