// CI sets KALIBABEL_MAVEN_MIRROR to route Maven Central traffic through a mirror (Google's CDN copy
// of Maven Central). Self-hosted runners otherwise hit Maven Central's per-IP rate limits (HTTP 429)
// on large artifacts such as Robolectric's runtime jar. Unset (local builds) = plain Maven Central.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        System.getenv("KALIBABEL_MAVEN_MIRROR")?.takeIf { it.isNotBlank() }?.let { url ->
            maven(url) { name = "mavenCentralMirror" }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        providers.environmentVariable("KALIBABEL_MAVEN_MIRROR").orNull?.takeIf { it.isNotBlank() }?.let { url ->
            maven(url) { name = "mavenCentralMirror" }
        }
        mavenCentral()
    }
}

rootProject.name = "KaliBabel"
include(":app")
