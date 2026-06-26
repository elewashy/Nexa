import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

fun signingProperty(name: String): String? = keystoreProperties.getProperty(name)?.takeIf { it.isNotBlank() }

val hasReleaseSigningConfig = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { signingProperty(it) != null }

val requestedReleaseBuild = gradle.startParameter.taskNames.any { taskName ->
    val normalized = taskName.lowercase()
    normalized.contains("assemblerelease") ||
        normalized.contains("bundlerelease") ||
        normalized.contains("packagerelease")
}

if (requestedReleaseBuild && !hasReleaseSigningConfig) {
    throw GradleException(
        "Release signing is not configured. Create an ignored keystore.properties file with " +
            "storeFile, storePassword, keyAlias, and keyPassword. In GitHub Actions configure " +
            "SIGNING_KEY, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD, and SIGNING_STORE_PASSWORD secrets."
    )
}

android {
    namespace = "com.elewashy.nexa"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.elewashy.nexa"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = rootProject.file(signingProperty("storeFile")!!)
                storePassword = signingProperty("storePassword")!!
                keyAlias = signingProperty("keyAlias")!!
                keyPassword = signingProperty("keyPassword")!!
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            versionNameSuffix = "-DEBUG"
        }
        
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = true
        warningsAsErrors = false
    }

    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        viewBinding = false
        buildConfig = true
        compose = true
    }
    
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/gradle/*"
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(output.versionName.map { "Nexa_V$it.apk" })
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-jvm-default=no-compatibility"
        )
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // Core library desugaring
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    
    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)

    // Jetpack Compose (BOM manages all Compose versions)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)
    
    // Gson
    implementation(libs.gson)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines)
    
    // OkHttp
    implementation(libs.okhttp)

    // Hilt - Dependency Injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // DataStore
    implementation(libs.datastore.preferences)

    // SplashScreen API
    implementation(libs.core.splashscreen)

    // Compose Icons (FontAwesome brands)
    implementation(libs.compose.icons.fontawesome)

    // Material color scheme generation
    implementation(libs.material.kolor)

    // Paging
    implementation(libs.paging.compose)

    // Markdown rendering
    implementation(libs.markdown.renderer)

    testImplementation(libs.junit)
}
