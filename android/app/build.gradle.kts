plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.trackmysugar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trackmysugar"
        minSdk = 29  // MediaStore.Downloads (used by export) needs API 29+
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
