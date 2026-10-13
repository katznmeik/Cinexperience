package io.github.katznmeik.cinexperience.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/** Bytecode level for all modules (the build itself runs on JDK 21). */
internal val JAVA_TARGET = JavaVersion.VERSION_17
internal val KOTLIN_JVM_TARGET = JvmTarget.JVM_17

internal const val BASE_PACKAGE = "io.github.katznmeik.cinexperience"

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalStateException("No library '$alias' in libs.versions.toml") }

internal fun VersionCatalog.versionInt(alias: String): Int =
    findVersion(alias).orElseThrow { IllegalStateException("No version '$alias' in libs.versions.toml") }
        .requiredVersion.toInt()

/** Namespace derived from the Gradle path, e.g. `:feature:cinemas` → `<base>.feature.cinemas`. */
internal fun Project.defaultNamespace(): String =
    BASE_PACKAGE + path.replace(':', '.').replace('-', '_')

/** Settings shared by application and library modules. */
internal fun Project.configureAndroidCommon(extension: CommonExtension) {
    extension.apply {
        compileSdk = libs.versionInt("compileSdk")
        defaultConfig.minSdk = libs.versionInt("minSdk")
        compileOptions.sourceCompatibility = JAVA_TARGET
        compileOptions.targetCompatibility = JAVA_TARGET
    }
    configureKotlinJvmTarget()
}

internal fun Project.configureKotlinJvmTarget() {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(KOTLIN_JVM_TARGET)
    }
}
