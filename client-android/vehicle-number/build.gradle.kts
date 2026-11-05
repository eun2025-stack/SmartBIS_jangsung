plugins {
    alias(libs.plugins.android.application)
}

val apiBaseUrl = providers.gradleProperty("smartbisApiBaseUrl")
    .orElse("https://192.168.1.5:8443/api")
    .get()
val appVersionCode = providers.gradleProperty("vehicleNumberVersionCode")
    .map { it.toInt() }
    .getOrElse(1)
val appVersionName = providers.gradleProperty("vehicleNumberVersionName")
    .orElse("1.0")
    .get()

android {
    namespace = "com.smartbis.vehiclenumber"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.smartbis.vehiclenumber"
        minSdk = 30
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "SMARTBIS_API_BASE_URL", "\"${apiBaseUrl.trimEnd('/')}\"")
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
}
