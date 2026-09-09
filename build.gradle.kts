import com.android.build.gradle.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.Properties

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.1.4")
        classpath("com.github.recloudstream:gradle:81b1d424d2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// Global metadata bypass
subprojects {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-Xannotation-default-target=param-property",
                "-Xskip-metadata-version-check"
            )
        }
    }
}

// Load secrets from local.properties if available
val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { load(it) }
    }
}

fun getSecret(key: String, fallback: String = ""): String {
    return localProperties.getProperty(key)
        ?: System.getenv(key)
        ?: fallback
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

// FIX: Use LibraryExtension instead of BaseExtension so compileSdk is available
fun Project.android(configuration: LibraryExtension.() -> Unit) =
    extensions.getByName<LibraryExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/ChocoCooper/cloudstream-repo")
        authors = listOf("ChocoCooper")
        isCrossPlatform = false
    }

    android {
        namespace = "com.chococooper.${project.name.lowercase()}"

        // FIX: compileSdk belongs here on LibraryExtension — now resolves correctly
        compileSdk = 35

        defaultConfig {
            minSdk = 21
            // FIX: targetSdk belongs inside defaultConfig per AGP 8.x
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions",
                    "-Xannotation-default-target=param-property",
                    "-Xskip-metadata-version-check"
                )
            }
        }
    }

    dependencies {
        val implementation by configurations
        val cloudstream by configurations

        cloudstream("com.lagradost:cloudstream3:pre-release")

        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.18")
        implementation("org.jsoup:jsoup:1.22.2")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.13.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        implementation("org.mozilla:rhino:1.8.1")
        implementation("me.xdrop:fuzzywuzzy:1.4.0")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        implementation("org.bouncycastle:bcpkix-jdk15to18:1.77")
    }

    // Skip cross-platform check task
    tasks.configureEach {
        if (name == "ensureJarCompatibility") {
            enabled = false
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
