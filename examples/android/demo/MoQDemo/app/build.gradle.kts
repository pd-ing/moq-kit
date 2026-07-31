import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Build identity: derive versionCode from the build time (yyMMddHH, UTC) so every
// APK is distinguishable, and expose the values to the app via BuildConfig.
val buildTimeMillis = System.currentTimeMillis()
val buildTimeInstant = Instant.ofEpochMilli(buildTimeMillis)
val generatedVersionCode = DateTimeFormatter
    .ofPattern("yyMMddHH")
    .withZone(ZoneOffset.UTC)
    .format(buildTimeInstant)
    .toInt()
val buildTimestamp = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm'Z'")
    .withZone(ZoneOffset.UTC)
    .format(buildTimeInstant)
val appVersionName = "1.0"

android {
    namespace = "com.swmansion.moqdemo"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.swmansion.moqdemo"
        minSdk = 29
        targetSdk = 36
        versionCode = generatedVersionCode
        versionName = appVersionName

        buildConfigField("String", "APP_VERSION_NAME", "\"$appVersionName\"")
        buildConfigField("int", "APP_VERSION_CODE", "$generatedVersionCode")
        buildConfigField("long", "BUILD_TIME_MILLIS", "${buildTimeMillis}L")
        buildConfigField("String", "BUILD_TIMESTAMP", "\"$buildTimestamp\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.swmansion.moqkit:moqkit:0.0.1-alpha3")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    val composeBom = platform("androidx.compose:compose-bom:2026.01.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Choose one of the following:
    // Material Design 3
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // or skip Material Design and build directly on top of foundational components
    implementation("androidx.compose.foundation:foundation")
    // or only import the main APIs for the underlying toolkit systems,
    // such as input and measurement/layout
    implementation("androidx.compose.ui:ui")

    // Android Studio Preview support
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
