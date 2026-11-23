package org.jetbrains.kotlinconf.bazel.metrocompat

import java.io.File
import kotlin.system.exitProcess

private const val USAGE = "usage: adapter --in <metro-compiler.jar> --shims <shims.jar> --out <adapted.jar>"

/** The command line of the `metro_native_compiler_jar` rule of //bazel:metro_compat.bzl. */
fun main(args: Array<String>) {
    var input: File? = null
    var shims: File? = null
    var out: File? = null
    try {
        val iterator = args.iterator()
        fun value(flag: String): String {
            require(iterator.hasNext()) { "$flag needs a value" }
            return iterator.next()
        }
        while (iterator.hasNext()) {
            when (val flag = iterator.next()) {
                "--in" -> input = File(value(flag))
                "--shims" -> shims = File(value(flag))
                "--out" -> out = File(value(flag))
                else -> throw IllegalArgumentException("unknown argument '$flag'")
            }
        }
        require(input != null) { "--in is required" }
        require(shims != null) { "--shims is required" }
        require(out != null) { "--out is required" }

        adaptMetroJar(input, shims, out)
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
        System.err.println(USAGE)
        exitProcess(1)
    }
}
