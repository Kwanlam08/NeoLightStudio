plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lightstickstudio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lightstickstudio"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: "neolightstudio.keystore"
            val keystorePass = System.getenv("KEYSTORE_PASSWORD") ?: "neolight2026"
            val keyAlias = System.getenv("KEY_ALIAS") ?: "neolightstudio"
            val keyPass = System.getenv("KEY_PASSWORD") ?: "neolight2026"
            storeFile = file(keystorePath)
            storePassword = keystorePass
            this.keyAlias = keyAlias
            keyPassword = keyPass
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("io.github.kyant0:backdrop:1.0.0")
    implementation("dev.chrisbanes.haze:haze:1.0.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
