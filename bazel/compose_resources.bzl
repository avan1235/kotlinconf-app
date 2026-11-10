"""compose_resources: the Compose Multiplatform resources of a module, as the Compose Gradle plugin prepares them.

The Gradle plugin (`org.jetbrains.compose`, configured with `compose.resources { ... }`) turns the
`src/<sourceSet>/composeResources/` directory of a module into:

- the `Res` class (`readBytes`, `getUri`) and the accessors of the resources, `Res.drawable.icon`,
  `Res.string.title`, `Res.plurals.items`, `Res.array.names`, `Res.font.body`;
- the collectors of all the resources of a type, `Res.allStringResources`: `expect` in the common
  source set and `actual` in every platform source set of a multiplatform module, plain declarations
  in a JVM-only module;
- the resource tree the runtime library (org.jetbrains.compose.components:components-resources)
  reads, under `composeResources/<package>/`: the drawables, fonts and raw files as they are, and the
  XML value resources converted to `.cvr` files (`values-de/strings.commonMain.cvr`) the accessors
  address by byte offset.

The `compose_resources` macro does the same with the generator of //bazel/compose_resources, at
build time (the generated code is not committed), with a fixed set of output sources:

    compose_resources(
        name = "shared_resources",
        package = "org.jetbrains.kotlinconf.generated.resources",
        resources_dir = "src/commonMain/composeResources",
        srcs = glob(["src/commonMain/composeResources/**"]),
    )

declares:

- `shared_resources`: the rule with every output;
- `shared_resources_common_srcs`: the sources of the common source set (`Res.kt`, the accessors,
  `ExpectResourceCollectors.kt`), for `common_srcs` of kt_multiplatform_library, or every source of a
  JVM-only module (`ResourceCollectors.kt` instead of the expect ones), for `srcs` of kt_jvm_library;
- `shared_resources_platform_srcs` (multiplatform only): `ActualResourceCollectors.kt`, the same file
  for every platform, for `srcs` of the `jvm`, `android` and native groups of the library;
- `shared_resources_jar`: the resource tree as a jar, for `resource_jars` of the JVM library;
- `shared_resources_files`: the resource tree as files, under `<name>/resources/`, for the Android
  assets (`assets = [":shared_resources_files"], assets_dir = "shared_resources/resources"`).

On iOS the runtime reads the resources of every module from one directory of the app bundle,
`<app>.app/compose-resources/`, where the Compose Gradle plugin syncs it from an Xcode build
(`syncComposeResourcesForIos`). `compose_resources_bundle` assembles that directory from the
`compose_resources` targets of the modules:

    compose_resources_bundle(
        name = "compose-resources",
        deps = ["//app/shared:shared_resources", "//app/ui-components:ui_components_resources"],
    )

is the directory `compose-resources/` with the trees of both modules
(`composeResources/<package>/...`), named after the target: a `structured_resources` entry of the
`apple_resource_group` of the app puts it at the root of the bundle under that name.
"""

load("@bazel_skylib//lib:paths.bzl", "paths")

ComposeResourcesInfo = provider(
    doc = "The prepared resource tree of a compose_resources target, for a packaging that takes a directory.",
    fields = {
        "files": "depset[File]: the files of the prepared tree.",
        "root": "str: the directory of the tree, the prefix of the short_path of every file before `composeResources/<package>/...`.",
    },
)

# The accessor files, one per resource type, whatever the resources of the module (a type without
# resources gets a file with only the package declaration): the outputs of the rule are fixed.
_ACCESSOR_FILES = ["Drawable", "String", "StringArray", "Plurals", "Font"]

# The names the generator gives the collector files (GeneratedFiles in Main.kt).
_EXPECT_COLLECTORS = "ExpectResourceCollectors.kt"
_ACTUAL_COLLECTORS = "ActualResourceCollectors.kt"
_COLLECTORS = "ResourceCollectors.kt"

def _prepared_path(relative_path, source_set):
    """The path of a resource in the prepared tree: `values*/<name>.xml` becomes `values*/<name>.<sourceSet>.cvr`."""
    parts = relative_path.split("/")
    if len(parts) == 2 and parts[0].startswith("values") and parts[1].lower().endswith(".xml"):
        return "%s/%s.%s.cvr" % (parts[0], parts[1][:-len(".xml")], source_set)
    return relative_path

def _compose_resources_impl(ctx):
    name = ctx.label.name
    package = ctx.attr.package
    source_set = ctx.attr.source_set
    root = paths.join(ctx.label.package, ctx.attr.resources_dir)
    prefix = root + "/"

    args = ctx.actions.args()
    args.set_param_file_format("multiline")
    args.use_param_file("@%s", use_always = True)
    args.add("--package", package)
    args.add("--res-class", ctx.attr.res_class)
    args.add("--source-set", source_set)
    if ctx.attr.public:
        args.add("--public")
    if ctx.attr.multiplatform:
        args.add("--multiplatform")

    inputs = []
    files = []
    for src in ctx.files.srcs:
        if not src.short_path.startswith(prefix):
            fail("compose_resources %s: %s is not under %s (resources_dir)" % (ctx.label, src.short_path, root))
        relative_path = src.short_path[len(prefix):]
        if relative_path.split("/")[-1].startswith("."):
            # Hidden files, e.g. .DS_Store, as the Gradle plugin skips them.
            continue
        inputs.append(src)
        args.add("--input", "%s=%s" % (relative_path, src.path))
        files.append(ctx.actions.declare_file(
            "%s/resources/composeResources/%s/%s" % (name, package, _prepared_path(relative_path, source_set)),
        ))
    if not inputs:
        fail("compose_resources %s: srcs has no resource file under %s" % (ctx.label, root))

    common_srcs = [ctx.actions.declare_file("%s/kotlin/common/%s.kt" % (name, ctx.attr.res_class))] + [
        ctx.actions.declare_file("%s/kotlin/common/%s.%s.kt" % (name, accessor_file, source_set))
        for accessor_file in _ACCESSOR_FILES
    ]
    platform_srcs = []
    if ctx.attr.multiplatform:
        common_srcs.append(ctx.actions.declare_file("%s/kotlin/common/%s" % (name, _EXPECT_COLLECTORS)))
        platform_srcs.append(ctx.actions.declare_file("%s/kotlin/platform/%s" % (name, _ACTUAL_COLLECTORS)))
    else:
        common_srcs.append(ctx.actions.declare_file("%s/kotlin/common/%s" % (name, _COLLECTORS)))
    jar = ctx.actions.declare_file(name + "_resources.jar")

    args.add("--kotlin-dir", common_srcs[0].dirname)
    if platform_srcs:
        args.add("--platform-kotlin-dir", platform_srcs[0].dirname)
    args.add("--resources-dir", paths.join(jar.dirname, name, "resources"))
    args.add("--jar", jar)

    # The jar first: Bazel names the params file after the first output, which keeps it out of the
    # source directories.
    outputs = [jar] + common_srcs + platform_srcs + files
    ctx.actions.run(
        outputs = outputs,
        inputs = inputs,
        executable = ctx.executable._generator,
        arguments = [args],
        mnemonic = "ComposeResources",
        progress_message = "Generating the Compose resources of %{label}",
    )
    return [
        DefaultInfo(files = depset(outputs)),
        OutputGroupInfo(
            common_srcs = depset(common_srcs),
            platform_srcs = depset(platform_srcs),
            files = depset(files),
            jar = depset([jar]),
        ),
        ComposeResourcesInfo(
            files = depset(files),
            root = paths.join(ctx.label.package, name, "resources"),
        ),
    ]

_compose_resources = rule(
    implementation = _compose_resources_impl,
    attrs = {
        "srcs": attr.label_list(
            doc = "The files of the composeResources directory, e.g. `glob([\"src/commonMain/composeResources/**\"])`.",
            allow_files = True,
            mandatory = True,
        ),
        "resources_dir": attr.string(
            doc = "The composeResources directory the srcs are in, relative to the package.",
            mandatory = True,
        ),
        "package": attr.string(
            doc = "The package of the Res class and the accessors (`packageOfResClass`), and the directory of the resources.",
            mandatory = True,
        ),
        "res_class": attr.string(
            doc = "The name of the Res class (`nameOfResClass`).",
            default = "Res",
        ),
        "public": attr.bool(
            doc = "Whether the Res class and the accessors are public instead of internal (`publicResClass`).",
            default = False,
        ),
        "source_set": attr.string(
            doc = "The Gradle source set of the resources, in the names of the .cvr files and collector functions.",
            default = "commonMain",
        ),
        "multiplatform": attr.bool(
            doc = "Whether the collectors are expect (common) and actual (platform) declarations, or plain ones of a JVM-only module.",
            default = True,
        ),
        "_generator": attr.label(
            default = Label("//bazel/compose_resources:generator"),
            executable = True,
            cfg = "exec",
        ),
    },
    doc = "Generates the Res class, accessors, collectors and the prepared resource tree of a composeResources directory.",
)

# Copies the files of the param file ($2, pairs of a source and a path in the directory) into the
# directory $1.
_COPY_TREE = """\
set -eu
out="$1"
params="${2#@}"
while IFS= read -r src && IFS= read -r path; do
  mkdir -p "$out/${path%/*}"
  cp "$src" "$out/$path"
done < "$params"
"""

def _compose_resources_bundle_impl(ctx):
    out = ctx.actions.declare_directory(ctx.label.name)
    args = ctx.actions.args()
    args.set_param_file_format("multiline")
    args.use_param_file("@%s", use_always = True)
    inputs = []
    providers = {}
    for dep in ctx.attr.deps:
        info = dep[ComposeResourcesInfo]
        prefix = info.root + "/"
        for file in info.files.to_list():
            if not file.short_path.startswith(prefix):
                fail("compose_resources_bundle %s: %s of %s is not under %s" % (ctx.label, file.short_path, dep.label, info.root))
            path = file.short_path[len(prefix):]
            if path in providers:
                fail("compose_resources_bundle %s: %s and %s both provide %s (the same package?)" % (ctx.label, providers[path], dep.label, path))
            providers[path] = dep.label
            inputs.append(file)
            args.add(file)
            args.add(path)
    if not inputs:
        fail("compose_resources_bundle %s: deps provide no resource file" % ctx.label)

    ctx.actions.run_shell(
        outputs = [out],
        inputs = inputs,
        command = _COPY_TREE,
        arguments = [out.path, args],
        mnemonic = "ComposeResourcesBundle",
        progress_message = "Assembling the compose-resources directory %{label}",
    )
    return [DefaultInfo(files = depset([out]))]

compose_resources_bundle = rule(
    implementation = _compose_resources_bundle_impl,
    attrs = {
        "deps": attr.label_list(
            doc = "The compose_resources targets of the modules whose resources the bundle holds.",
            providers = [ComposeResourcesInfo],
            mandatory = True,
        ),
    },
    doc = """Assembles the `compose-resources` directory of an iOS app bundle: one directory, named after the target, with the
    prepared trees of the deps (`composeResources/<package>/...`), where the Compose resources runtime of iOS reads
    them (`<app>.app/compose-resources/`).""",
)

def compose_resources(
        name,
        package,
        resources_dir,
        srcs,
        res_class = "Res",
        public = False,
        source_set = "commonMain",
        multiplatform = True,
        visibility = None,
        **kwargs):
    """Declares the Compose resources of a module: the rule and the filegroups of its outputs (see the module doc).

    Args:
      name: the name of the rule; the filegroups are `<name>_common_srcs`, `<name>_platform_srcs`
        (multiplatform only), `<name>_jar` and `<name>_files`.
      package: the package of the Res class and of the resources, Gradle's `packageOfResClass`.
      resources_dir: the composeResources directory, relative to the package, e.g. `src/commonMain/composeResources`.
      srcs: the files of that directory, e.g. `glob(["src/commonMain/composeResources/**"])`.
      res_class: the name of the Res class, Gradle's `nameOfResClass`.
      public: whether the Res class and the accessors are public, Gradle's `publicResClass`.
      source_set: the name of the Gradle source set of the resources, e.g. `main` for a JVM-only module.
      multiplatform: False for a JVM-only module, whose collectors are plain declarations.
      visibility: the visibility of the targets.
      **kwargs: more attributes common to every rule, e.g. `tags` or `testonly`.
    """
    _compose_resources(
        name = name,
        package = package,
        resources_dir = resources_dir,
        srcs = srcs,
        res_class = res_class,
        public = public,
        source_set = source_set,
        multiplatform = multiplatform,
        visibility = visibility,
        **kwargs
    )
    output_groups = ["common_srcs", "files", "jar"] + (["platform_srcs"] if multiplatform else [])
    for output_group in output_groups:
        native.filegroup(
            name = "%s_%s" % (name, output_group),
            srcs = [":" + name],
            output_group = output_group,
            visibility = visibility,
            **kwargs
        )
