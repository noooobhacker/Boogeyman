plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.boogeyman.app"
    compileSdk = 34
    defaultConfig { applicationId = "com.boogey.man"; minSdk = 29; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.webrtc:google-webrtc:1.0.32006")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
}
