import com.diffplug.gradle.spotless.SpotlessExtension
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask
import dev.detekt.gradle.extensions.DetektExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.openapi.generator) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt) apply false
}

configure<SpotlessExtension> {
    kotlin {
        target(
            "**/src/main/**/*.kt",
            "**/src/test/**/*.kt",
            "**/src/androidTest/**/*.kt",
        )
        targetExclude(
            "**/build/**",
            "**/generated/**",
            "**/ksp/**",
            "**/kapt/**",
            "**/vendor/**",
            "**/src/main/openapi/**",
        )
        ktlint(libs.versions.ktlint.get())
            .setEditorConfigPath(rootProject.file(".editorconfig").path)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint(libs.versions.ktlint.get())
            .setEditorConfigPath(rootProject.file(".editorconfig").path)
    }
}

val detektVersion = libs.versions.detekt.get()
val composeRulesVersion = libs.versions.composeRules.get()

subprojects {
    apply(plugin = "dev.detekt")

    configure<DetektExtension> {
        toolVersion.set(detektVersion)
        config.setFrom(rootProject.file("detekt.yml"))
        buildUponDefaultConfig.set(false)
        parallel.set(true)
        ignoreFailures.set(false)
    }

    dependencies {
        add(
            "detektPlugins",
            "io.nlopez.compose.rules:detekt:$composeRulesVersion",
        )
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget.set("17")
        exclude("**/build/**", "**/generated/**", "**/ksp/**", "**/kapt/**", "**/vendor/**")
        // Variant sources include generated directories as separate file-tree roots.
        exclude { entry ->
            entry.file.invariantSeparatorsPath.split('/').any {
                it in setOf("build", "generated", "ksp", "kapt", "vendor")
            }
        }
    }
    tasks.withType<DetektCreateBaselineTask>().configureEach {
        jvmTarget.set("17")
        exclude("**/build/**", "**/generated/**", "**/ksp/**", "**/kapt/**", "**/vendor/**")
        exclude { entry ->
            entry.file.invariantSeparatorsPath.split('/').any {
                it in setOf("build", "generated", "ksp", "kapt", "vendor")
            }
        }
    }
    tasks.matching { it.name == "check" }.configureEach {
        // Analyze production code and both kinds of tests with type resolution.
        dependsOn(
            tasks.withType<Detekt>().matching {
                it.name in setOf("detektDebug", "detektDebugUnitTest", "detektDebugAndroidTest")
            },
        )
    }
}
