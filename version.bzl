"""The version of the KotlinConf apps: the one place to bump for a release.

//app/androidApp puts it in the manifest of the APK (versionName, versionCode), //app/iosApp in the
Info.plist of the app (CFBundleShortVersionString, CFBundleVersion), and //app/shared in the
`app_version` string resource of the About screen ("<name> (<code>)").
"""

# The version users see: the versionName of the Android app, the CFBundleShortVersionString of the
# iOS app.
VERSION_NAME = "40.0.5"

# The build number, to increase with every store upload: the versionCode of the Android app, the
# CFBundleVersion of the iOS app.
VERSION_CODE = "73"
