plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.rokoucha.visiomata.settings.data"
    compileSdk = 37

    defaultConfig { minSdk = 33 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.androidx.preference.ktx)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
