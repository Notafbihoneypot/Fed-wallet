plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.independentwallet.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        // Side-by-side test channel; never require deleting an earlier test vault.
        applicationId = "app.independentwallet.android.syncpreview2"
        minSdk = 28
        targetSdk = 36
        versionCode = 29
        versionName = "0.11.11-preview2-candidate"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    signingConfigs.getByName("debug") {
        // Preview 2 has its own identity; never recreate the lost original key.
        // Keep the protected key and password outside the source archive.
        storeFile = rootProject.file(providers.environmentVariable("IW_SIGNING_KEYSTORE").orNull
            ?: "../.private-signing/preview2.p12")
        storeType = "PKCS12"
        keyAlias = "independentwallet-preview2"
        storePassword = providers.environmentVariable("IW_SIGNING_PASSWORD").orNull
        keyPassword = storePassword
    }
    testOptions.unitTests.all {
        // Real host UniFFI calls, using the library built by build-bindings.sh.
        it.systemProperty("jna.library.path", rootProject.file("../target/debug").absolutePath)
        it.inputs.file(rootProject.file("../target/debug/" + System.mapLibraryName("independent_wallet")))
            .withPropertyName("hostRustLibrary")
    }
    buildTypes {
        getByName("debug") {
            // The delivered preview must not expose its live wallet to an
            // Android/Java or native debugger. Keep host tests on this code path.
            isDebuggable = false
            isJniDebuggable = false
            if (providers.gradleProperty("unsignedPreview").orNull == "true") signingConfig = null
        }
        release {
            // Owner signs the APK after downloading it; never use the debug key.
            signingConfig = null
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging { jniLibs.useLegacyPackaging = false }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.java.dev.jna:jna:5.17.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

val previewApkFileName = if (providers.gradleProperty("unsignedPreview").orNull == "true") "app-debug-unsigned.apk" else "app-debug.apk"

val verifyDebugApkIntegrity by tasks.registering(Exec::class) {
    dependsOn("packageDebug")
    commandLine("python3", rootProject.file("../scripts/check-apk.py").absolutePath,
        layout.buildDirectory.file("outputs/apk/debug/$previewApkFileName").get().asFile.absolutePath)
}
tasks.matching { it.name == "assembleDebug" }.configureEach { dependsOn(verifyDebugApkIntegrity) }

val verifyDebugAndroidTestApkIntegrity by tasks.registering(Exec::class) {
    dependsOn("packageDebugAndroidTest")
    commandLine("python3", rootProject.file("../scripts/check-apk.py").absolutePath,
        layout.buildDirectory.file("outputs/apk/androidTest/debug/app-debug-androidTest.apk").get().asFile.absolutePath)
}
tasks.matching { it.name == "assembleDebugAndroidTest" }.configureEach { dependsOn(verifyDebugAndroidTestApkIntegrity) }
