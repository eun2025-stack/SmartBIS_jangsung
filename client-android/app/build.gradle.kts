plugins {
    alias(libs.plugins.android.application)
}

val smartbisApiBaseUrl = providers.gradleProperty("smartbisApiBaseUrl")
    .orElse("https://192.168.1.5:8443/api")
    .get()
val smartbisVersionCode = providers.gradleProperty("smartbisVersionCode")
    .map { it.toInt() }
    .getOrElse(15)
val smartbisVersionName = providers.gradleProperty("smartbisVersionName")
    .orElse("1.13")
    .get()

android {
    namespace = "com.smartbis.client"
    buildFeatures { buildConfig = true }
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.smartbis.client"
        minSdk = 30
        targetSdk = 37
        versionCode = smartbisVersionCode
        versionName = smartbisVersionName
        buildConfigField("String", "SMARTBIS_API_BASE_URL", "\"${smartbisApiBaseUrl.trimEnd('/')}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
