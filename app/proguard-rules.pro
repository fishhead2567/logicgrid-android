# Empty on purpose, and worth saying why: an empty rules file reads like
# something nobody got round to.
#
# The usual reason an app this shape needs keep rules is reflective JSON --
# kotlinx.serialization's generated serializers are found by reflection, so
# R8 renames the classes they describe and parsing dies at runtime, on a
# device, in a release build nobody tested.
#
# This app has none of that. core/PuzzleJson.kt is hand-written against
# JsonElement rather than @Serializable data classes, so no serializer is ever
# looked up by name, and nothing here reads a class or a field reflectively.
# Compose and Material 3 ship their own consumer rules.
#
# Verified rather than assumed: :app:assembleRelease shrinks 9.8 MB down to
# 1.0 MB with no R8 warnings and no missing-class diagnostics, and the bundled
# content survives -- all 50 puzzle files are still in the release APK.
#
# If a @Serializable class is ever added, this is where its keep rule goes.
