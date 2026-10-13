plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firebase's Google Services plugin is enabled only after a real config file is added.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "in.examdarpan.vaanix"
    compileSdk = 35

    defaultConfig {
        applicationId = "in.examdarpan.vaanix"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
}

tasks.register("printGoogleServices") {
    doLast {
        val file = file("google-services.json")
        if (file.exists()) {
            println("FILE_CONTENT_START")
            println(file.readText())
            println("FILE_CONTENT_END")
        } else {
            println("File not found at ${file.absolutePath}")
        }
    }
}
