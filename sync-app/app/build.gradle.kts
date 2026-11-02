plugins {
    id("com.android.application")
}

android {
    namespace = "com.tod4s3.sync"
    compileSdk = 35

    androidResources {
        noCompress += "dat"
    }

    defaultConfig {
        applicationId = "com.tod4s3.sync"
        minSdk = 23
        targetSdk = 35
        versionCode = 48
        versionName = "1.5.3"
    }
}
