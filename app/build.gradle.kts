plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// GitHub Actions passes the build number so every APK can install over the previous one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.oluwafemi.tg2mt5"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.oluwafemi.tg2mt5"
        minSdk = 24
        targetSdk = 34
        versionCode = buildNumber
        versionName = "2.0.$buildNumber"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Two versions of the app from the same code, installable side by side:
    //  flexible: one channel can feed many MT5 accounts (updates the existing app, keeps its data)
    //  pairs:    each channel is paired with exactly one MT5 account (separate app)
    flavorDimensions += "links"
    productFlavors {
        create("flexible") {
            dimension = "links"
            buildConfigField("boolean", "STRICT_PAIRS", "false")
            resValue("string", "app_name", "TG2MT5 Copier")
        }
        create("pairs") {
            dimension = "links"
            applicationIdSuffix = ".pairs"
            buildConfigField("boolean", "STRICT_PAIRS", "true")
            resValue("string", "app_name", "TG2MT5 Pairs")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "tg2mt5copier"
            keyAlias = "copier"
            keyPassword = "tg2mt5copier"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // TDLib (downloaded by the build workflow into app/tdlib): Java classes + native libraries.
    sourceSets {
        getByName("main") {
            java.srcDir("tdlib/java")
            jniLibs.srcDir("tdlib/libs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
            it.systemProperty("roborazzi.output.dir", rootProject.file("screenshots").absolutePath)
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.8.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // Screenshot tests of the app screens (run on the build server, not on the phone)
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.20.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.20.0")
    testImplementation(platform("androidx.compose:compose-bom:2024.09.03"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
