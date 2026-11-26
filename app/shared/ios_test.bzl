"""iOS unit test wrapper for //app/shared:tests.

Two things are needed on top of rules_apple's ios_unit_test to link //app/shared:tests_ios:
1. //app/shared depends on KMPNotifier, whose iOS klib calls into FirebaseMessaging's FIRMessaging
   Objective-C class, so the test bundle links @swiftpkg_firebase_ios_sdk//:FirebaseMessaging with
   -ObjC and -lsqlite3 (as //app/iosApp/BUILD.bazel does).
2. rules_kotlin's kt_native_xctest_library sets alwayslink = True (-force_load) on the Kotlin/Native
   static archive (.a). For a module that depends on Compose Multiplatform / Skiko, konanc bundles
   Skiko's embedded static archives with duplicate .o members (e.g. 5 copies of libzlib, 4 copies of
   Skia objects), so -force_load fails ld with 20k+ duplicate symbols. Keeping alwayslink = True on
   the XCTest shim (.lo) alone still pulls the single Kotlin/Native object file (and all test
   initializers) via the shim's call to rules_kotlin_xctest_run_*, while letting ld resolve Skiko's
   duplicate archive members on demand without -force_load.
"""

load("@rules_apple//apple:ios.bzl", "ios_unit_test")
load("@rules_cc//cc:find_cc_toolchain.bzl", "find_cc_toolchain", "use_cc_toolchain")
load("@rules_cc//cc/common:cc_common.bzl", "cc_common")
load("@rules_cc//cc/common:cc_info.bzl", "CcInfo")

def _drop_archive_alwayslink_impl(ctx):
    cc_toolchain = find_cc_toolchain(ctx)
    feature_configuration = cc_common.configure_features(
        ctx = ctx,
        cc_toolchain = cc_toolchain,
        requested_features = ctx.features,
        unsupported_features = ctx.disabled_features,
    )
    linker_inputs = []
    for dep in ctx.attr.deps:
        for li in dep[CcInfo].linking_context.linker_inputs.to_list():
            libraries = []
            for lib in li.libraries:
                if lib.static_library and lib.static_library.extension == "a" and lib.alwayslink:
                    libraries.append(cc_common.create_library_to_link(
                        actions = ctx.actions,
                        feature_configuration = feature_configuration,
                        cc_toolchain = cc_toolchain,
                        static_library = lib.static_library,
                        alwayslink = False,
                    ))
                else:
                    libraries.append(lib)
            linker_inputs.append(cc_common.create_linker_input(
                owner = ctx.label,
                libraries = depset(libraries),
                user_link_flags = li.user_link_flags,
                additional_inputs = depset(li.additional_inputs),
            ))
    return [CcInfo(linking_context = cc_common.create_linking_context(
        linker_inputs = depset(linker_inputs),
    ))]

_drop_archive_alwayslink = rule(
    implementation = _drop_archive_alwayslink_impl,
    attrs = {
        "deps": attr.label_list(providers = [CcInfo]),
    },
    fragments = ["cpp"],
    toolchains = use_cc_toolchain(),
)

def shared_ios_unit_test(name, deps = [], linkopts = [], tags = [], **kwargs):
    unwrapped = name + "_unwrapped_lib"
    _drop_archive_alwayslink(
        name = unwrapped,
        deps = deps,
        tags = tags + ["manual"],
        target_compatible_with = ["@platforms//os:ios"],
        testonly = True,
        visibility = ["//visibility:private"],
    )
    ios_unit_test(
        name = name,
        deps = [":" + unwrapped, "@swiftpkg_firebase_ios_sdk//:FirebaseMessaging"],
        linkopts = linkopts + [
            "-ObjC",
            "-lsqlite3",
        ],
        tags = tags,
        **kwargs
    )
