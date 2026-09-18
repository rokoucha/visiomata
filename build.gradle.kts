import com.atkinsondev.opentelemetry.build.OpenTelemetryBuildPluginExtension
import com.atkinsondev.opentelemetry.build.OpenTelemetryExporterMode
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
    alias(libs.plugins.opentelemetry.build)
}

// OpenTelemetry build traces. Enabled only when an OTLP traces endpoint is configured
// (CI sets OTEL_EXPORTER_OTLP_TRACES_ENDPOINT via setup-otel-collector). An explicitly
// empty value disables tracing for auxiliary Gradle invocations.
val otlpTracesEndpoint = System.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT").orEmpty()

configure<OpenTelemetryBuildPluginExtension> {
    endpoint.set(otlpTracesEndpoint.ifBlank { "http://127.0.0.1:4318/v1/traces" })
    exporterMode.set(OpenTelemetryExporterMode.HTTP)
    enabled.set(otlpTracesEndpoint.isNotBlank())
    serviceName.set("visiomata-builds")
    traceViewUrl.set(
        System.getenv("OTEL_TRACE_VIEW_URL").takeUnless { it.isNullOrBlank() }
            ?: "https://mackerel.io/orgs/rokoucha/traces/{traceId}",
    )
    // OTEL_EXPORTER_OTLP_HEADERS ("key1=value1,key2=value2") for direct backend export.
    // Unused when exporting via the local Collector, which attaches auth headers itself.
    headers.set(
        System
            .getenv("OTEL_EXPORTER_OTLP_HEADERS")
            .orEmpty()
            .split(",")
            .mapNotNull { entry ->
                val key = entry.substringBefore("=").trim()
                val value = entry.substringAfter("=", missingDelimiterValue = "").trim()
                if (key.isEmpty() || value.isEmpty() || !entry.contains("=")) null else key to value
            }.toMap(),
    )
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
