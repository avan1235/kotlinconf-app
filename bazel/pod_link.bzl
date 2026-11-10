"""Consumer-side CocoaPods link targets for rules_kmp's @maven hub.

kmpnotifier's iOS klibs are published with the FirebaseMessaging CocoaPod as their native link
dependency: the `link_pods` mapping of the maven extension (maven/extension.bzl:255) records a
pod per platform in maven_lock.json, and the hub's maven_klib targets take a consumer-supplied
link_<pod>_<platform> target that provides rules_kmp's NativeLinkInfo (maven/import.bzl:96-103).
rules_kmp deliberately ships no rule for that contract — the consumer decides what linking the
pod means in their workspace.

For this app the pod is the Firebase Messaging the Swift Package Manager build of app/iosApp
already produces: the archives of @swiftpkg_firebase_ios_sdk//:FirebaseMessaging (rules_swift_
package_manager, the Package.swift pin of MODULE.bazel). A static framework is not linked — it
contains the Kotlin code only, and the app links Firebase itself (kmp_native_framework doc,
kotlin/private/native.bzl), so the ios_application needs nothing here. The test executables of
the Kotlin/Native targets do link, however, and the klibs' manifests bake `-framework
FirebaseMessaging` into their linkerOpts (konan adds the flag at link time, and ld fails if the
framework it names is nowhere in the -F paths). The rule satisfies that flag with an empty
FirebaseMessaging.framework: the pod's code enters through the by-path archive closure of `deps`
(the app's own way of linking Firebase — apple_link_info, @rules_kmp//apple:native_deps.bzl),
and the empty framework cannot load it a second time, which would clash the members the closure
already defines (the same reason apple_link_info links framework binaries by name).
"""

load("@rules_kmp//apple:native_deps.bzl", "SWIFT_RUNTIME_ATTR", "apple_link_info")
load("@rules_kmp//apple:transitions.bzl", "apple_target_transition")
load("@rules_kmp//kotlin:providers.bzl", "NativeLinkInfo")

def _pod_link_impl(ctx):
    info = apple_link_info(ctx.attr.deps, ctx.attr._swift_runtime, ctx.attr.linkopts)

    # The framework the klibs' linkerOpts name. Its binary is an archive of zero members — the
    # header `!<arch>` alone is what `ar -cr` writes for an empty archive — because the code it
    # stands for is in the archives `deps` link by path.
    framework = ctx.actions.declare_file("%s.framework/%s" % (ctx.attr.framework, ctx.attr.framework))
    ctx.actions.write(framework, "!<arch>\n")
    infoplist = ctx.actions.declare_file("%s.framework/Info.plist" % ctx.attr.framework)
    ctx.actions.write(
        infoplist,
        '<?xml version="1.0" encoding="UTF-8"?>\n<plist version="1.0">\n<dict/>\n</plist>\n',
    )

    linkopts = depset(
        ["-F" + framework.dirname.rsplit("/", 1)[0], "-framework " + ctx.attr.framework],
        transitive = [info.linkopts],
    )
    inputs = depset([framework, infoplist], transitive = [info.inputs])
    return [
        NativeLinkInfo(
            linkopts = linkopts,
            inputs = inputs,
            runtime_files = info.runtime_files,
        ),
    ]

pod_link = rule(
    implementation = _pod_link_impl,
    doc = "Provides the NativeLinkInfo of a CocoaPod the @maven hub's link_pods mapping names.",
    attrs = {
        "framework": attr.string(
            mandatory = True,
            doc = "The name of the pod's framework, e.g. FirebaseMessaging.",
        ),
        "target": attr.string(
            mandatory = True,
            doc = "The Kotlin/Native target the pod links for, e.g. ios_simulator_arm64.",
        ),
        "deps": attr.label_list(
            cfg = apple_target_transition,
            doc = "The pod and its dependencies, built for the Apple platform of `target`.",
        ),
        "linkopts": attr.string_list(doc = "Additional linker flags for executables that link the pod."),
        "_swift_runtime": SWIFT_RUNTIME_ATTR,
    },
)
