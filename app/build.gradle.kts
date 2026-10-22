plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlinx.kover")
}

val storePath = System.getenv("SIGNING_STORE_FILE")
val storePasswordValue = System.getenv("SIGNING_STORE_PASSWORD")
val keyAliasValue = System.getenv("SIGNING_KEY_ALIAS")
val keyPasswordValue = System.getenv("SIGNING_KEY_PASSWORD")
val hasSigning = listOf(storePath, storePasswordValue, keyAliasValue, keyPasswordValue).all { !it.isNullOrBlank() }

android {
    namespace = "dev.muhib.s23optimizer"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.muhib.s23optimizer"
        minSdk = 29
        targetSdk = 35
        // 2 belongs to the separate v2.0.0-alpha1 pre-release line.
        versionCode = 3
        versionName = System.getenv("RELEASE_VERSION") ?: "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (hasSigning) create("environment") {
            storeFile = file(requireNotNull(storePath))
            storePassword = storePasswordValue
            keyAlias = keyAliasValue
            keyPassword = keyPasswordValue
        }
    }
    buildTypes {
        debug { isDebuggable = true }
        release {
            isMinifyEnabled = false
            // With no secrets, debug remains buildable and release is unsigned.
            signingConfig = if (hasSigning) signingConfigs.getByName("environment") else null
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions {
        unitTests.isReturnDefaultValues = false
        // Robolectric needs merged resources and the manifest for the UI smoke test.
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "2g" }
    }
}

kover {
    reports {
        filters {
            excludes {
                // Generated code: Room DAOs, Compose lambdas, BuildConfig.
                classes("*_Impl", "*_Impl\$*", "*ComposableSingletons*", "dev.muhib.s23optimizer.BuildConfig")
            }
        }
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit-ktx:1.2.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
