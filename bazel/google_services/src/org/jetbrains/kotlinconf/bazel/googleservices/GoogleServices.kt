package org.jetbrains.kotlinconf.bazel.googleservices

import java.util.TreeMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The string resources the Google Services Gradle plugin (`com.google.gms.google-services`) generates
 * from `google-services.json` for the client of an application id, by name, as its `values.xml` has
 * them (GoogleServicesTask): the project number as `gcm_defaultSenderId`, `project_id`, the optional
 * `google_storage_bucket` and `firebase_database_url`, the client's `google_app_id`, its API key as
 * `google_api_key` and `google_crash_reporting_api_key`, the optional `default_web_client_id` (the
 * OAuth client of type 3) and `ga_trackingId`.
 */
fun googleServicesValues(json: String, packageName: String): Map<String, String> {
    val root = Json.parseToJsonElement(json).jsonObject
    val values = TreeMap<String, String>()

    val projectInfo = root.obj("project_info") ?: missing("project_info")
    values["gcm_defaultSenderId"] = projectInfo.string("project_number") ?: missing("project_info/project_number")
    values["project_id"] = projectInfo.string("project_id") ?: missing("project_info/project_id")
    projectInfo.string("storage_bucket")?.let { values["google_storage_bucket"] = it }
    projectInfo.string("firebase_url")?.let { values["firebase_database_url"] = it }

    val client = root["client"]?.jsonArray
        ?.map { it.jsonObject }
        ?.firstOrNull { it.obj("client_info")?.obj("android_client_info")?.string("package_name") == packageName }
        ?: throw IllegalArgumentException("google-services.json has no client with the package name '$packageName'")
    values["google_app_id"] = client.obj("client_info")?.string("mobilesdk_app_id")?.takeIf { it.isNotEmpty() }
        ?: missing("client/client_info/mobilesdk_app_id")
    val apiKey = client["api_key"]?.jsonArray?.firstNotNullOfOrNull { it.jsonObject.string("current_key") }
        ?: missing("client/api_key/current_key")
    values["google_api_key"] = apiKey
    values["google_crash_reporting_api_key"] = apiKey
    client["oauth_client"]?.jsonArray
        ?.map { it.jsonObject }
        ?.firstOrNull { it.string("client_type") == "3" }
        ?.string("client_id")
        ?.let { values["default_web_client_id"] = it }
    client.obj("services")?.obj("analytics_service")?.obj("analytics_property")?.string("tracking_id")
        ?.let { values["ga_trackingId"] = it }
    return values
}

/** The `values.xml` of the Gradle plugin: one non-translatable string per value, in the order of the names. */
fun valuesXml(values: Map<String, String>): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
    for ((name, value) in values) {
        append("    <string name=\"").append(name).append("\" translatable=\"false\">")
        append(androidString(value))
        append("</string>\n")
    }
    append("</resources>\n")
}

/**
 * A value as the text of a `<string>` resource: the XML markup characters as entities, and the
 * characters aapt2 reads as escapes or references in a string resource (quotes, backslashes, a leading
 * `@` or `?`) escaped with a backslash, so the resource is the value.
 */
fun androidString(value: String): String {
    val escaped = StringBuilder(value.length)
    for (c in value) {
        when (c) {
            '&' -> escaped.append("&amp;")
            '<' -> escaped.append("&lt;")
            '>' -> escaped.append("&gt;")
            '\\' -> escaped.append("\\\\")
            '\'' -> escaped.append("\\'")
            '"' -> escaped.append("\\\"")
            else -> escaped.append(c)
        }
    }
    if (escaped.startsWith("@") || escaped.startsWith("?")) escaped.insert(0, '\\')
    return escaped.toString()
}

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.content

private fun missing(path: String): Nothing = throw IllegalArgumentException("google-services.json is missing $path")
