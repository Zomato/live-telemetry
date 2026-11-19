pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "live-telemetry-root"

include(":live-telemetry")

// The sample app is skipped when this repo is consumed as an included (composite) build,
// unless the host opts in with `compositeBuild.includeSampleApps=true` in its local.properties.
fun shouldIncludeSampleApp(): Boolean {
    val parent = gradle.parent ?: return true
    return try {
        val localProps = java.util.Properties()
        val file = java.io.File(parent.startParameter.projectDir, "local.properties")
        if (file.exists()) file.inputStream().use { localProps.load(it) }
        localProps.getProperty("compositeBuild.includeSampleApps") == "true"
    } catch (_: Throwable) {
        false
    }
}

if (shouldIncludeSampleApp()) {
    include(":sample")
}
