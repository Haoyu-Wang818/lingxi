plugins {
    id("com.android.library")
}

android {
    namespace = "com.lingxi.sync.datalayer"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:sync"))
    implementation(libs.play.services.wearable)
}
