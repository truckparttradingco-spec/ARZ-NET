```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePath = System.getenv("KEYSTORE_PATH")
val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
val signingKeyAlias = System.getenv("KEY_ALIAS")
val signingKeyPassword = System.getenv("KEY_PASSWORD")

android {
    namespace = "com.arznet.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arznet.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"

        buildConfigField(
            "String",
            "TRADING_ECONOMICS_API_KEY",
            "\"${System.getenv("TRADING_ECONOMICS_API_KEY") ?: ""}\""
        )
    }

    signingConfigs {
        create("release") {
            if (!keystorePath.isNullOrBlank()) {
                require(!keystorePassword.isNullOrBlank()) {
                    "KEYSTORE_PASSWORD is missing"
                }
                require(!signingKeyAlias.isNullOrBlank()) {
                    "KEY_ALIAS is missing"
                }
                require(!signingKeyPassword.isNullOrBlank()) {
                    "KEY_PASSWORD is missing"
                }

                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
```
