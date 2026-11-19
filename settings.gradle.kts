rootProject.name = "logicgrid-android"

pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}

// :core is pure Kotlin/JVM on purpose -- it must stay buildable and testable
// with nothing but a JDK, so the solving logic is never gated on an emulator.
include(":core")
