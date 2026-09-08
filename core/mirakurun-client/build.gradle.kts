import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.openapi.generator)
}

val generatedMirakurunDir = layout.buildDirectory.dir("generated/openapi/mirakurun")
val generatedMahironDir = layout.buildDirectory.dir("generated/openapi/mahiron")
val arrangedKotlinDir = layout.buildDirectory.dir("generated/kotlin")

android {
    namespace = "net.rokoucha.visiomata.mirakurun"
    compileSdk = 37

    defaultConfig { minSdk = 33 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory("build/generated/kotlin")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:network"))
    implementation(project(":core:settings-data"))
    api(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.moshi.adapters)

    testImplementation(libs.junit)
}

fun GenerateTask.configureClient(
    specName: String,
    specFile: String,
    generatedDir: String,
) {
    generatorName.set("kotlin")
    library.set("jvm-okhttp4")
    inputSpec.set(
        layout.projectDirectory
            .file(specFile)
            .asFile.path,
    )
    outputDir.set(generatedDir)
    val rootPackage = "net.rokoucha.visiomata.$specName"
    packageName.set(rootPackage)
    apiPackage.set("$rootPackage.api")
    modelPackage.set("$rootPackage.model")
    globalProperties.set(
        mapOf(
            "apiDocs" to "false",
            "apiTests" to "false",
            "modelDocs" to "false",
            "modelTests" to "false",
            "generateAliasAsModel" to "true",
        ),
    )
    configOptions.set(
        mapOf(
            "dateLibrary" to "string",
            "enumPropertyNaming" to "UPPERCASE",
            "serializationLibrary" to "moshi",
            "useCoroutines" to "true",
            "useSettingsGradle" to "false",
            "hideGenerationTimestamp" to "true",
            "generateAliasAsModel" to "true",
        ),
    )
}

val generateMirakurunApi =
    tasks.register<GenerateTask>("generateMirakurunApi") {
        configureClient(
            specName = "mirakurun",
            specFile = "src/main/openapi/mirakurun.json",
            generatedDir = generatedMirakurunDir.get().asFile.path,
        )
    }

val generateMahironApi =
    tasks.register<GenerateTask>("generateMahironApi") {
        configureClient(
            specName = "mahiron",
            specFile = "src/main/openapi/mahiron.yml",
            generatedDir = generatedMahironDir.get().asFile.path,
        )
    }

val arrangeGeneratedApis =
    tasks.register<Sync>("arrangeGeneratedApis") {
        dependsOn(generateMirakurunApi, generateMahironApi)
        from(generatedMirakurunDir.map { it.dir("src/main/kotlin") })
        from(generatedMahironDir.map { it.dir("src/main/kotlin") })
        include("**/*.kt")
        eachFile {
            // Keep only <server>/<api|model|infrastructure>/<file>, rather than the
            // complete package directory or one completely flat source directory.
            path = relativePath.segments.takeLast(3).joinToString("/")
        }
        includeEmptyDirs = false
        into(arrangedKotlinDir)
    }

tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach {
    dependsOn(arrangeGeneratedApis)
}

tasks.matching { it.name.startsWith("extract") && it.name.endsWith("Annotations") }.configureEach {
    dependsOn(arrangeGeneratedApis)
}
