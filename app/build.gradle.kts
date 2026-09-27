plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val ciKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val ciKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val ciKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val hasCiSigning = listOf(
    ciKeystorePath,
    ciKeystorePassword,
    ciKeyAlias,
    ciKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.example.cookierunbot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.cookierunbot"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        if (hasCiSigning) {
            create("ci") {
                storeFile = file(requireNotNull(ciKeystorePath))
                storePassword = requireNotNull(ciKeystorePassword)
                keyAlias = requireNotNull(ciKeyAlias)
                keyPassword = requireNotNull(ciKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            if (hasCiSigning) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core:1.16.0")
    implementation("org.opencv:opencv:4.13.0")
    testImplementation("junit:junit:4.13.2")
}
