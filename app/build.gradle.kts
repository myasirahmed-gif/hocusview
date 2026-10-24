plugins {
    id("com.android.application")
}
android {
    namespace = "com.hocus.vision"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.hocus.vision"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    // Android Studio's JDK 25 needs a newer bytecode reader in the host-side tests.
    listOf("asm", "asm-commons", "asm-tree", "asm-util", "asm-analysis").forEach {
        testImplementation("org.ow2.asm:$it:9.9.1")
    }
}
