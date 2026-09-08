plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.rokoucha.visiomata.network"
    compileSdk = 37

    defaultConfig { minSdk = 33 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
