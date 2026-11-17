import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing credentials live only in a local, git-ignored keystore.properties (the same Layerbit upload key as
// every other Layerbit app) - never hardcoded here and never committed.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasKeystoreProperties = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply { if (hasKeystoreProperties) keystorePropertiesFile.inputStream().use { load(it) } }

/** Brand name has one source: core/.../Brand.kt (CLAUDE.md naming rule). */
val brandName: String = Regex("""NAME\s*=\s*"([^"]+)"""")
    .find(rootProject.file("core/src/main/kotlin/app/penny/core/Brand.kt").readText())!!.groupValues[1]

android {
    namespace = "app.penny"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Play listing id, like every Layerbit app (com.layerbit.<app>); the code namespace stays app.penny.
        applicationId = "com.layerbit.penny"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 3
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "brand_name", brandName)
    }

    signingConfigs {
        if (hasKeystoreProperties) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isPseudoLocalesEnabled = true
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystoreProperties) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        resValues = true
        buildConfig = true
    }
    testOptions { unitTests.all { it.useJUnitPlatform() } }
    androidResources { generateLocaleConfig = false }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

// Test builds keep the package they always had (app.penny.debug), so the copy on the owner's phone and its data carry on;
// only the Play release uses com.layerbit.penny.
androidComponents {
    onVariants(selector().withBuildType("debug")) { it.applicationId.set("app.penny.debug") }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":packs"))
    implementation(project(":data"))
    implementation(project(":capture"))
    implementation(project(":sync"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.play.billing)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.play.services.wearable)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
