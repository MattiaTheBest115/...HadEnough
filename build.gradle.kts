import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import com.android.build.gradle.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        // Shitpack repo which contains our tools and dependencies
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")
        // Cloudstream gradle plugin which makes everything work and builds plugins
        classpath("com.github.recloudstream:gradle:7cc64f92d8")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.resolveRepositoryUrl(): String {
    val githubRepository = System.getenv("GITHUB_REPOSITORY")
        ?.takeIf { it.isNotBlank() }
        ?.let { repo ->
            if (repo.startsWith("http://") || repo.startsWith("https://")) repo
            else "https://github.com/$repo"
        }
    if (githubRepository != null) return githubRepository

    val gitConfig = rootProject.file(".git/config")
    if (gitConfig.exists()) {
        var insideOrigin = false
        var originUrl: String? = null

        gitConfig.forEachLine { line ->
            val trimmed = line.trim()
            when {
                trimmed == "[remote \"origin\"]" -> insideOrigin = true
                trimmed.startsWith("[") -> insideOrigin = false
                insideOrigin && trimmed.startsWith("url = ") -> {
                    originUrl = trimmed.removePrefix("url = ").removeSuffix(".git")
                }
            }
        }

        if (!originUrl.isNullOrBlank()) {
            return originUrl!!
        }
    }

    return "https://github.com/doGior/doGiorsHadEnough"
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: LibraryExtension.() -> Unit) {
    extensions.getByName<LibraryExtension>("android").apply {
        configuration()
    }
}

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(resolveRepositoryUrl())
        authors = listOf("doGior")
    }

    android {
        namespace = "it.dogior.hadEnough"
        compileSdk = 36

        defaultConfig {
            minSdk = 21
        }

        lint {
            targetSdk = 36
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions",
                    "-Xannotation-default-target=param-property"
                )
            }
        }
    }

    dependencies {
        val implementation by configurations
        val cloudstream by configurations

        // Stubs for all Cloudstream classes
        cloudstream("com.lagradost:cloudstream3:pre-release")

        // these dependencies can include any of those which are added by the app,
        // but you dont need to include any of them if you dont need them
        // https://github.com/recloudstream/cloudstream/blob/master/app/build.gradle
        implementation(kotlin("stdlib")) // adds standard kotlin features
        implementation("com.github.Blatzar:NiceHttp:0.4.11") // http library
        implementation("org.jsoup:jsoup:1.18.1") // html parser
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
