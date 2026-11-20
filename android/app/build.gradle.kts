plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.payasito.yasuobuild"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.payasito.yasuobuild"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    /* Clave fija para que cada APK nuevo se instale encima del anterior sin borrar nada.
       Es una app personal que se instala a mano: la clave no protege nada importante. */
    signingConfigs {
        create("fija") {
            storeFile = file("yasuo.keystore")
            storePassword = "yasuobuild"
            keyAlias = "yasuo"
            keyPassword = "yasuobuild"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fija")
        }
        debug { signingConfig = signingConfigs.getByName("fija") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
