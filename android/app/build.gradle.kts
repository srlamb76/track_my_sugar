plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// One version for web and Android: resources/public/version.txt ("MAJOR.MINOR.PATCH").
// The web header shows it, versionName is it, and versionCode is derived from it.
val appVersion = file("../../resources/public/version.txt").readText().trim()
val versionParts = appVersion.split(".").map { it.toInt() }
require(versionParts.size == 3) { "version.txt must look like 1.2.3, was '$appVersion'" }

// The launch screen shows the version as part of an image, so a version change
// needs `python3 branding/make_assets.py`; fail the build rather than ship a stale one.
val checkBranding by tasks.registering {
    val wordmark = file("../branding/wordmark.svg")
    inputs.files(wordmark, file("../../resources/public/version.txt"))
    doLast {
        check(wordmark.readText().contains("Version $appVersion")) {
            "The launch screen artwork doesn't show version $appVersion. Run: python3 android/branding/make_assets.py"
        }
    }
}
tasks.named("preBuild") { dependsOn(checkBranding) }

// The instrumented tests install the app and test APK, then uninstall them, which
// deletes the app's data. Refuse to run them on anything but an emulator unless
// told otherwise, so a real phone's readings can't be wiped by accident.
tasks.configureEach {
    if (name.startsWith("connected") && name.endsWith("AndroidTest")) {
        doFirst {
            val serial = System.getenv("ANDROID_SERIAL") ?: ""
            check(serial.startsWith("emulator-") || project.hasProperty("allowRealDevices")) {
                "Instrumented tests uninstall the app, which deletes its data. Run them on an emulator: " +
                    "ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest " +
                    "(or pass -PallowRealDevices to override)."
            }
        }
    }
}

android {
    namespace = "com.trackmysugar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trackmysugar"
        minSdk = 29  // MediaStore.Downloads (used by export) needs API 29+
        targetSdk = 35
        versionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 shrinks code and resources, which cuts the install size and the
            // memory the app loads. proguard-rules.pro keeps the JS bridge.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Reuse the web front end and SQL schema from the Clojure project, so
    // there is one copy of each. The front end is served from /assets/public/.
    sourceSets["main"].assets.srcDirs(
        "../../resources",   // -> public/index.html, app.js, style.css
        "../../schema",      // -> schema.sql, default_load.sql
    )
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core-splashscreen:1.2.0")  // launch screen on all supported versions

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
