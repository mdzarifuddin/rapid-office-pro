// This machine gets 403 from repo.maven.apache.org (the host mavenCentral() defaults to) but can
// reach repo1.maven.org fine, so Maven Central is declared explicitly by URL.
val mavenCentralMirror = "https://repo1.maven.org/maven2"

pluginManagement {
    repositories {
        google()
        maven { url = uri("https://repo1.maven.org/maven2") }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { url = uri(mavenCentralMirror) }
    }
}

rootProject.name = "RapidPDF"
include(":app")
