package org.jetbrains.kotlinconf.bazel.googleservices.tests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.jetbrains.kotlinconf.bazel.googleservices.androidString
import org.jetbrains.kotlinconf.bazel.googleservices.googleServicesValues
import org.jetbrains.kotlinconf.bazel.googleservices.valuesXml

/** The generator on a google-services.json of made-up values: two clients, one with a web OAuth client. */
class GoogleServicesTest {
    private val json = """
        {
          "project_info": {
            "project_number": "123456789012",
            "firebase_url": "https://example-project.firebaseio.com",
            "project_id": "example-project",
            "storage_bucket": "example-project.appspot.com"
          },
          "client": [
            {
              "client_info": {
                "mobilesdk_app_id": "1:123456789012:android:00000000000000000000aa",
                "android_client_info": { "package_name": "org.example.other" }
              },
              "oauth_client": [],
              "api_key": [ { "current_key": "other-api-key" } ],
              "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
            },
            {
              "client_info": {
                "mobilesdk_app_id": "1:123456789012:android:00000000000000000000bb",
                "android_client_info": { "package_name": "org.example.app" }
              },
              "oauth_client": [
                { "client_id": "123456789012-android.apps.googleusercontent.com", "client_type": 1 },
                { "client_id": "123456789012-web.apps.googleusercontent.com", "client_type": 3 }
              ],
              "api_key": [ { "current_key": "example-api-key" } ],
              "services": {
                "appinvite_service": { "other_platform_oauth_client": [] },
                "analytics_service": { "analytics_property": { "tracking_id": "UA-00000000-1" } }
              }
            }
          ],
          "configuration_version": "1"
        }
    """.trimIndent()

    @Test
    fun `the values of the client of the package name, as the Gradle plugin names them`() {
        assertEquals(
            mapOf(
                "default_web_client_id" to "123456789012-web.apps.googleusercontent.com",
                "firebase_database_url" to "https://example-project.firebaseio.com",
                "ga_trackingId" to "UA-00000000-1",
                "gcm_defaultSenderId" to "123456789012",
                "google_api_key" to "example-api-key",
                "google_app_id" to "1:123456789012:android:00000000000000000000bb",
                "google_crash_reporting_api_key" to "example-api-key",
                "google_storage_bucket" to "example-project.appspot.com",
                "project_id" to "example-project",
            ),
            googleServicesValues(json, "org.example.app"),
        )
    }

    @Test
    fun `the optional values are left out`() {
        assertEquals(
            mapOf(
                "gcm_defaultSenderId" to "123456789012",
                "google_api_key" to "other-api-key",
                "google_app_id" to "1:123456789012:android:00000000000000000000aa",
                "google_crash_reporting_api_key" to "other-api-key",
                "google_storage_bucket" to "example-project.appspot.com",
                "project_id" to "example-project",
                "firebase_database_url" to "https://example-project.firebaseio.com",
            ),
            googleServicesValues(json, "org.example.other"),
        )
    }

    @Test
    fun `a package name without a client is an error`() {
        val error = assertFailsWith<IllegalArgumentException> { googleServicesValues(json, "org.example.missing") }
        assertEquals("google-services.json has no client with the package name 'org.example.missing'", error.message)
    }

    @Test
    fun `a missing required value is an error`() {
        val error = assertFailsWith<IllegalArgumentException> {
            googleServicesValues("""{"project_info": {"project_number": "1"}, "client": []}""", "org.example.app")
        }
        assertEquals("google-services.json is missing project_info/project_id", error.message)
    }

    @Test
    fun `the xml is the values file of the Gradle plugin, sorted by name`() {
        assertEquals(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="gcm_defaultSenderId" translatable="false">123456789012</string>
                <string name="google_api_key" translatable="false">other-api-key</string>
                <string name="google_app_id" translatable="false">1:123456789012:android:00000000000000000000aa</string>
                <string name="google_crash_reporting_api_key" translatable="false">other-api-key</string>
                <string name="google_storage_bucket" translatable="false">example-project.appspot.com</string>
                <string name="project_id" translatable="false">example-project</string>
            </resources>

            """.trimIndent(),
            valuesXml(googleServicesValues(json, "org.example.other").filterKeys { it != "firebase_database_url" }),
        )
    }

    @Test
    fun `the markup and the string resource escapes of a value are escaped`() {
        assertEquals("""a &amp; b &lt;c&gt; \"d\" \'e\' f\\g""", androidString("""a & b <c> "d" 'e' f\g"""))
        assertEquals("""\@string/x""", androidString("@string/x"))
        assertEquals("""\?attr/x""", androidString("?attr/x"))
        assertEquals("a@b?c", androidString("a@b?c"))
    }
}
