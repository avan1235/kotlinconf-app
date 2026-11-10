package org.jetbrains.kotlinconf.bazel.googleservices

import java.io.File
import kotlin.system.exitProcess

private const val USAGE = "usage: generator --json <google-services.json> --package <application id> --out <values xml>"

/** The command line of the `google_services_resources` rule of //bazel:google_services.bzl. */
fun main(args: Array<String>) {
    var json: File? = null
    var packageName: String? = null
    var out: File? = null
    try {
        val iterator = args.iterator()
        fun value(flag: String): String {
            require(iterator.hasNext()) { "$flag needs a value" }
            return iterator.next()
        }
        while (iterator.hasNext()) {
            when (val flag = iterator.next()) {
                "--json" -> json = File(value(flag))
                "--package" -> packageName = value(flag)
                "--out" -> out = File(value(flag))
                else -> throw IllegalArgumentException("unknown argument '$flag'")
            }
        }
        require(json != null) { "--json is required" }
        require(!packageName.isNullOrEmpty()) { "--package is required" }
        require(out != null) { "--out is required" }

        val values = googleServicesValues(json.readText(), packageName)
        out.parentFile?.mkdirs()
        out.writeText(valuesXml(values))
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
        System.err.println(USAGE)
        exitProcess(1)
    }
}
