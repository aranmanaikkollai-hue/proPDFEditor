pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ProPDF Editor"
include(":app")
include(":core")
include(":viewer")
include(":editor")
include(":annotations")
include(":scanner")
include(":security")
include(":ads")
