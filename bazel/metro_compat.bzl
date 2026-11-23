"""metro_native_compiler_jar: adapts the Metro compiler plugin jar for the Kotlin/Native compiler release.

rules_kotlin pins Kotlin/Native to a 2.5.20-dev build (for the Kotlin/Native Build Tools API) apart
from the 2.4.21 JVM compiler toolchain, while Metro 1.6.0's built-in compatibility layer currently
targets up to Kotlin 2.5.20-dev-4223. Between 2.5.20-dev-5510 and 2.5.20-dev-5696 three internal
compiler symbols moved:

- `DiagnosticFactory{0,1,2}DelegateProvider.<init>` dropped its `KClass` parameter;
- `org.jetbrains.kotlin.ir.interpreter.UtilsKt.hasAnnotation` moved to `org.jetbrains.kotlin.ir.util.IrUtilsKt`;
- `ConeTypeParameterLookupTag` moved from `org.jetbrains.kotlin.fir.symbols` to `org.jetbrains.kotlin.fir.types`.

`metro_native_compiler_jar` rewrites those references in a copy of the Metro compiler jar and bundles
thin delegate shims so `//:metro_native_plugin` runs in Kotlin/Native 2.5.20-dev-5696 while JVM and
Android keep the unmodified `//:metro_plugin`.
"""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")

def _metro_native_compiler_jar_impl(ctx):
    jars = ctx.attr.src[JavaInfo].runtime_output_jars
    if len(jars) != 1:
        fail("metro_native_compiler_jar %s: expected 1 runtime jar from %s, got %s" % (ctx.label, ctx.attr.src.label, jars))
    src_jar = jars[0]
    shims_jars = ctx.attr._shims[JavaInfo].runtime_output_jars
    if len(shims_jars) != 1:
        fail("metro_native_compiler_jar %s: expected 1 shims jar, got %s" % (ctx.label, shims_jars))
    shims_jar = shims_jars[0]

    out = ctx.actions.declare_file(ctx.label.name + ".jar")
    args = ctx.actions.args()
    args.add("--in", src_jar)
    args.add("--shims", shims_jar)
    args.add("--out", out)
    ctx.actions.run(
        outputs = [out],
        inputs = [src_jar, shims_jar],
        executable = ctx.executable._adapter,
        arguments = [args],
        mnemonic = "MetroNativePluginAdapt",
        progress_message = "Adapting Metro compiler plugin %{label} for Kotlin/Native",
    )
    return [
        DefaultInfo(files = depset([out])),
        JavaInfo(
            output_jar = out,
            compile_jar = out,
        ),
    ]

metro_native_compiler_jar = rule(
    implementation = _metro_native_compiler_jar_impl,
    attrs = {
        "src": attr.label(
            doc = "The Metro compiler jar target providing JavaInfo.",
            providers = [JavaInfo],
            mandatory = True,
        ),
        "_shims": attr.label(
            default = Label("//bazel/metro_compat:shims_lib"),
            providers = [JavaInfo],
            cfg = "exec",
        ),
        "_adapter": attr.label(
            default = Label("//bazel/metro_compat:adapter"),
            executable = True,
            cfg = "exec",
        ),
    },
    doc = "Adapts the Metro compiler plugin jar to the internal FIR/IR symbols of Kotlin/Native 2.5.20-dev-5696.",
)
