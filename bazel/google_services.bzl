"""google_services_resources: the Firebase resources of an Android app, from its google-services.json.

The Google Services Gradle plugin (`com.google.gms.google-services`, applied by app/androidApp) reads
the `google-services.json` of the module at build time and generates a `values.xml` of string
resources the Firebase libraries read when they initialize (`google_app_id`, `google_api_key`,
`gcm_defaultSenderId`, `project_id`, ...). The rule does the same with the generator of
//bazel/google_services, so the JSON stays the only copy of the configuration:

    google_services_resources(
        name = "google_services",
        src = "google-services.json",
        package = "com.jetbrains.kotlinconf",
    )

    kt_android_library(
        resource_files = glob(["src/main/res/**"]) + [":google_services"],
        ...
    )

The output is `<name>/res/values/google-services.xml`, a `values` resource file for `resource_files`.
Only the values are generated, not the `xml/global_tracker.xml` of a client with Google Analytics.
"""

def _google_services_resources_impl(ctx):
    out = ctx.actions.declare_file(ctx.label.name + "/res/values/google-services.xml")

    args = ctx.actions.args()
    args.add("--json", ctx.file.src)
    args.add("--package", ctx.attr.package)
    args.add("--out", out)
    ctx.actions.run(
        outputs = [out],
        inputs = [ctx.file.src],
        executable = ctx.executable._generator,
        arguments = [args],
        mnemonic = "GoogleServicesResources",
        progress_message = "Generating the Firebase resources of %{label}",
    )
    return [DefaultInfo(files = depset([out]))]

google_services_resources = rule(
    implementation = _google_services_resources_impl,
    attrs = {
        "src": attr.label(
            doc = "The google-services.json of the app.",
            allow_single_file = [".json"],
            mandatory = True,
        ),
        "package": attr.string(
            doc = "The application id of the app, the `package_name` of its client in the file.",
            mandatory = True,
        ),
        # The generator is the generator_bin java_binary of the kmp_jvm_module below (the old
        # migration's kt_jvm_binary was named generator; kmp_jvm_module appends _bin).
        "_generator": attr.label(
            default = Label("//bazel/google_services:generator_bin"),
            executable = True,
            cfg = "exec",
        ),
    },
    doc = "Generates the string resources of google-services.json for an application id, as the Google Services Gradle plugin does.",
)
