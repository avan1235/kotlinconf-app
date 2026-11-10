"""Files of the Kotlin/Native targets, built on the host, for an Apple-configured consumer.

rules_kmp compiles Kotlin/Native on the macOS host only (the klibs and the frameworks of
kmp_library carry target_compatible_with = MACOS_ONLY, and the konan links of the iOS targets
cross-compile from there), while the iOS app of //app/iosApp builds its deps in the Apple platform
of --ios_multi_cpus. host_files bridges the two: its deps build in the host configuration (the
default platform of the machine), and the files they produce reach the Apple configuration as
plain files, for apple_static_framework_import to import into the app bundle.
"""

def _host_transition_impl(settings, attr):
    # @platforms//host:host is the auto-configured platform of the machine Bazel runs on.
    return {"//command_line_option:platforms": "@platforms//host:host"}

_host_transition = transition(
    implementation = _host_transition_impl,
    inputs = [],
    outputs = ["//command_line_option:platforms"],
)

def _host_files_impl(ctx):
    files = depset(transitive = [dep[DefaultInfo].files for dep in ctx.attr.deps])
    return [
        DefaultInfo(files = files),
        # The default output group of the framework (its files) is what apple_static_framework_import
        # consumes; the compose resources ride along in the group of the same name.
        OutputGroupInfo(**{
            name: depset(transitive = [dep[OutputGroupInfo][name] for dep in ctx.attr.deps])
            for group in [dep[OutputGroupInfo] for dep in ctx.attr.deps]
            for name in group
        }),
    ]

host_files = rule(
    implementation = _host_files_impl,
    doc = "The files of deps built in the host configuration, for an Apple-configured consumer.",
    attrs = {
        "deps": attr.label_list(cfg = _host_transition),
        "_allowlist_function_transition": attr.label(
            default = "@bazel_tools//tools/allowlists/function_transition_allowlist:function_transition_allowlist",
        ),
    },
)
