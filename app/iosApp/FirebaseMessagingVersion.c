/*
 * The two version globals the CocoaPods build of the FirebaseMessaging pod defines and
 * kmpnotifier's FirebaseMessaging cinterop links against (see bazel/pod_link.bzl): CocoaPods
 * bakes them into every pod it builds, but rules_swift_package_manager builds Firebase from
 * the Swift package sources, which do not have them. The values are the Firebase version
 * Package.resolved pins (11.15.0), in the format CocoaPods generates: the number is
 * major * 10000 + minor * 100 + patch, the string the dotted version.
 */

const double FirebaseMessagingVersionNumber = 111500.0;
const unsigned char FirebaseMessagingVersionString[] = "11.15.0";
