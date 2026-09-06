@file:Suppress("ktlint:standard:max-line-length")

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

abstract class DownloadVerifiedFile : DefaultTask() {
    @get:Input abstract val sourceUrl: Property<String>

    @get:Input abstract val expectedSha256: Property<String>

    @get:OutputFile abstract val destinationFile: RegularFileProperty

    @TaskAction fun download() {
        val destination = destinationFile.get().asFile
        destination.parentFile.mkdirs()
        val temporary = destination.resolveSibling("${destination.name}.part")
        try {
            URI(sourceUrl.get()).toURL().openStream().use { input ->
                Files.copy(input, temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            val actual =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(temporary.readBytes())
                    .joinToString("") { "%02x".format(it) }
            check(actual == expectedSha256.get()) {
                "Downloaded file SHA-256 mismatch: expected ${expectedSha256.get()}, got $actual"
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}

abstract class PrepareMpeg2ToH264 : DefaultTask() {
    @get:InputDirectory abstract val sourceDirectory: DirectoryProperty

    @get:InputDirectory abstract val testDataSourceDirectory: DirectoryProperty

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @get:OutputDirectory abstract val testDataOutputDirectory: DirectoryProperty

    @TaskAction fun prepare() {
        fun copyTree(
            sourceDirectory: DirectoryProperty,
            outputDirectory: DirectoryProperty,
        ) {
            val sourceRoot = sourceDirectory.get().asFile.toPath()
            val outputRoot = outputDirectory.get().asFile.toPath()
            if (Files.exists(outputRoot)) {
                Files.walk(outputRoot).sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
            Files.walk(sourceRoot).forEach { source ->
                val destination = outputRoot.resolve(sourceRoot.relativize(source).toString())
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination)
                } else {
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }

        copyTree(sourceDirectory, outputDirectory)
        copyTree(testDataSourceDirectory, testDataOutputDirectory)

        val outputRoot = outputDirectory.get().asFile.toPath()
        val adts = outputRoot.resolve("src/container/adts.rs").toFile()
        var code = adts.readText()
        val dualMonoConfig =
            buildString {
                appendLine("    pub config: AacConfig,")
                appendLine("    /// Whether this access unit carried main and subordinate mono services.")
                appendLine("    pub is_dual_mono: bool,")
                append("}")
            }
        code =
            code.replace(
                "    pub config: AacConfig,\n}",
                dualMonoConfig,
            )
        val initialDualMonoConfig =
            buildString {
                appendLine("        config: config.clone(),")
                appendLine("        is_dual_mono: false,")
                append("    })")
            }
        code = code.replace("        config: config.clone(),\n    })", initialDualMonoConfig)
        val dualMonoFrame =
            buildString {
                appendLine("            output.push(AacFrame {")
                appendLine("                data,")
                appendLine("                config,")
                appendLine("                is_dual_mono,")
                append("            });")
            }
        code = code.replace("            output.push(AacFrame { data, config });", dualMonoFrame)
        adts.writeText(code)
    }
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use(::load)
    }
val aribFontAssetsDirectory = layout.projectDirectory.dir("src/main/assets/fonts")
val kosugiMaruFontFile = aribFontAssetsDirectory.file("KosugiMaru-Regular.ttf")
val downloadKosugiMaruFont =
    tasks.register<DownloadVerifiedFile>("downloadKosugiMaruFont") {
        description = "Downloads and verifies the primary ARIB caption font"
        group = "build setup"
        sourceUrl.set("https://raw.githubusercontent.com/google/fonts/main/apache/kosugimaru/KosugiMaru-Regular.ttf")
        expectedSha256.set("4b8d0022c8dadd090ef67cd1f71f130714767af7806cba2eb4ebe4b0271c1d68")
        destinationFile.set(kosugiMaruFontFile)
    }
val aribFallbackFontFile = aribFontAssetsDirectory.file("rounded-mplus-1m-wadalab-comp-arib.ttf")
val downloadAribFallbackFont =
    tasks.register<DownloadVerifiedFile>("downloadAribFallbackFont") {
        description = "Downloads and verifies the supplemental ARIB caption font"
        group = "build setup"
        sourceUrl.set(
            "https://github.com/vivid-lapin/rounded-mplus-wadalab-mix/releases/download/202606272034/" +
                "rounded-mplus-1m-wadalab-comp-arib.ttf",
        )
        expectedSha256.set("ab46396819ffbe89a44dc2465123238d6e2f4da05625351fdfc356de1923ead4")
        destinationFile.set(aribFallbackFontFile)
    }
val webBmlWrapperDirectory = layout.projectDirectory.dir("web-bml-wrapper")
val npmExecutable =
    System.getenv("NPM")
        ?: if (System.getProperty("os.name").startsWith("Windows", true)) {
            "npm.cmd"
        } else {
            listOf(
                file("/opt/homebrew/bin/npm"),
                file("/usr/local/bin/npm"),
            ).firstOrNull(File::isFile)?.absolutePath ?: "npm"
        }
val installWebBmlDependencies =
    tasks.register<Exec>("installWebBmlDependencies") {
        description = "Installs the locked web-bml wrapper dependencies"
        group = "build setup"
        workingDir(webBmlWrapperDirectory)
        commandLine(npmExecutable, "ci", "--no-audit", "--no-fund")
        inputs.files(
            webBmlWrapperDirectory.file("package.json"),
            webBmlWrapperDirectory.file("package-lock.json"),
        )
        outputs.dir(webBmlWrapperDirectory.dir("node_modules"))
    }
val buildWebBmlBundle =
    tasks.register<Exec>("buildWebBmlBundle") {
        description = "Bundles the Android bridge against the locked web-bml package"
        group = "build"
        workingDir(webBmlWrapperDirectory)
        commandLine(npmExecutable, "run", "build")
        dependsOn(installWebBmlDependencies)
        inputs.dir(webBmlWrapperDirectory.dir("src"))
        inputs.files(
            webBmlWrapperDirectory.file("package.json"),
            webBmlWrapperDirectory.file("package-lock.json"),
            webBmlWrapperDirectory.file("tsconfig.json"),
        )
        outputs.files(
            layout.projectDirectory.file("src/main/assets/web-bml/play_local.js"),
            layout.projectDirectory.file("src/main/assets/web-bml/play_local.js.LEGAL.txt"),
        )
    }
val copyWebBmlAssets =
    tasks.register<Copy>("copyWebBmlAssets") {
        description = "Copies the pinned web-bml fonts and license files into Android assets"
        group = "build setup"
        dependsOn(installWebBmlDependencies)
        into(layout.projectDirectory.dir("src/main/assets/web-bml"))
        from(webBmlWrapperDirectory.dir("node_modules/web-bml/fonts")) {
            include("*.woff2", "LICENSE.txt")
            rename("LICENSE.txt", "LICENSE-fonts.txt")
        }
        from(webBmlWrapperDirectory.dir("node_modules/web-bml")) {
            include("LICENSE")
            rename("LICENSE", "LICENSE-web-bml.txt")
        }
    }

android {
    namespace = "net.rokoucha.visiomata.playback"
    compileSdk = 37
    defaultConfig {
        minSdk = 33
        ndkVersion = "28.2.13676358"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

tasks.named("preBuild").configure {
    dependsOn(
        downloadKosugiMaruFont,
        downloadAribFallbackFont,
        buildWebBmlBundle,
        copyWebBmlAssets,
    )
}

val rustDirectory = layout.projectDirectory.dir("src/main/rust")
val prepareMpeg2ToH264 =
    tasks.register<PrepareMpeg2ToH264>("prepareMpeg2ToH264") {
        description = "Copies and patches the pinned mpeg2toh264 submodule"
        group = "build setup"
        sourceDirectory.set(rustDirectory.dir("vendor/mpeg2toh264/crates/mpeg2toh264"))
        testDataSourceDirectory.set(rustDirectory.dir("vendor/mpeg2toh264/testdata"))
        outputDirectory.set(rustDirectory.dir("generated/mpeg2toh264"))
        testDataOutputDirectory.set(rustDirectory.dir("testdata"))
    }
val androidSdkDirectory =
    file(
        localProperties.getProperty("sdk.dir")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: System.getenv("ANDROID_HOME")
            ?: error("Android SDK path is not configured"),
    )
val ndkHostTag =
    when {
        System.getProperty("os.name").startsWith("Mac", true) -> "darwin-x86_64"
        System.getProperty("os.name").startsWith("Windows", true) -> "windows-x86_64"
        else -> "linux-x86_64"
    }
val androidNdkToolchain =
    androidSdkDirectory.resolve(
        "ndk/${android.ndkVersion}/toolchains/llvm/prebuilt/$ndkHostTag/bin",
    )
val cargoExecutable =
    System.getenv("CARGO")
        ?: listOf(
            file("${System.getProperty("user.home")}/.cargo/bin/cargo"),
            file("/opt/homebrew/opt/rustup/bin/cargo"),
            file("/usr/local/bin/cargo"),
        ).firstOrNull(File::isFile)?.absolutePath
        ?: "cargo"
val rustAndroidTargets =
    mapOf(
        "Arm64" to ("aarch64-linux-android" to "aarch64-linux-android23-clang"),
        "ArmV7" to ("armv7-linux-androideabi" to "armv7a-linux-androideabi23-clang"),
        "X86" to ("i686-linux-android" to "i686-linux-android23-clang"),
        "X8664" to ("x86_64-linux-android" to "x86_64-linux-android23-clang"),
    )
val cargoBuildAndroidTasks =
    rustAndroidTargets.map { (suffix, pair) ->
        val (target, linker) = pair
        tasks.register<Exec>("cargoBuildAndroid$suffix") {
            description = "Builds the MPEG-2 to H.264 bridge for $target"
            group = "build"
            workingDir(rustDirectory)
            val executable = if (ndkHostTag.startsWith("windows")) "$linker.cmd" else linker
            environment(
                "CARGO_TARGET_${target.uppercase().replace('-', '_')}_LINKER",
                androidNdkToolchain.resolve(executable),
            )
            commandLine(cargoExecutable, "build", "--release", "--target", target, "-p", "visiomata-mpeg2toh264")
            dependsOn(prepareMpeg2ToH264)
            inputs.dir(rustDirectory.dir("visiomata-mpeg2toh264"))
            inputs.dir(rustDirectory.dir("vendor/mpeg2toh264/crates/mpeg2toh264"))
            inputs.dir(rustDirectory.dir("testdata"))
            inputs.file(rustDirectory.file("Cargo.toml"))
            outputs.file(rustDirectory.file("target/$target/release/libvisiomata_mpeg2toh264.a"))
        }
    }
val cargoBuildAndroid = tasks.register("cargoBuildAndroid") { dependsOn(cargoBuildAndroidTasks) }
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(cargoBuildAndroid)
}

kotlin { jvmToolchain(17) }

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.extractor)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
}
