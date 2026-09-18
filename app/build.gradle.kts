plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val buildCommit = providers.environmentVariable("BUILD_COMMIT").orElse("unknown")
val buildTime = providers.environmentVariable("BUILD_TIME").orElse("unknown")

android {
    namespace = "com.example.cookierunbot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.cookierunbot"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "BUILD_COMMIT", buildCommit.get().asBuildConfigString())
        buildConfigField("String", "BUILD_TIME", buildTime.get().asBuildConfigString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
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
    implementation("org.opencv:opencv:4.13.0")
    testImplementation("junit:junit:4.13.2")
}
