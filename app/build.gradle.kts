import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.catsmoker.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.catsmoker.app"
        minSdk = 27
        targetSdk = 36
        // versionCode 7: IFileService gained readFile/writeFile — the bump is what forces
        // Shizuku to restart the daemonized helper whose AIDL no longer matches (see the
        // ShellRunner KDoc). Shipped helpers keep serving the old AIDL until this moves.
        versionCode = 7
        versionName = "2.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }

        val localProperties = Properties()
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(localPropertiesFile.inputStream())
        }
        val startIoId = localProperties.getProperty("STARTIO_APP_ID") ?: "205489527"
        buildConfigField("String", "STARTIO_APP_ID", "\"$startIoId\"")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("debug") {
            isMinifyEnabled = false
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = false
        compose = true
        buildConfig = true
        aidl = true // Required for Shizuku UserService
    }

    // Prevents build failure if minor warnings occur
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    ndkVersion = "27.0.12077973"

    packaging {
        resources {
            excludes += listOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0"
            )
        }
    }
}

dependencies {
    // --- AndroidX Core & UI ---
    implementation(libs.material) // Theme.Material3.* parents in res/values/themes.xml
    implementation(libs.activity)
    implementation(libs.core)
    implementation(libs.documentfile)
    implementation(libs.core.splashscreen)
    implementation(libs.core.ktx)

    // --- Jetpack Compose ---
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.runtime.tracing)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.ktx)

    // --- Hilt DI ---
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    // @HiltWorker needs hilt-work's factory plus its own annotation processor.
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // --- Background scheduling (the recurring dexopt sweep) ---
    implementation(libs.work.runtime.ktx)

    // --- Coroutines ---
    implementation(libs.kotlinx.coroutines.android)

    // --- JSON ---
    implementation(libs.gson)

    // --- Ads ---
    implementation(libs.startio.sdk)

    // --- Root & System ---
    implementation(libs.libsu.core)
    compileOnly(libs.api) // Xposed API

    // --- Shizuku ---
    implementation(libs.shizuku.api)
    implementation(libs.provider)

    // --- Testing ---
    testImplementation(libs.junit)
    testImplementation(libs.json.test)
}

ksp {
    arg("dagger.fastInit", "ENABLED")
    arg("dagger.hilt.android.internal.disableAndroidSuperclassValidation", "true")
}