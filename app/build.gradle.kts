plugins {
    id("com.android.application")
}

android {
    namespace = "com.muy257.themecompat"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.muy257.themecompat"
        // Personal HyperOS 3 adapter. API 35+ also natively supports the
        // Java runtime classes used by the public LSPosed service bridge.
        minSdk = 35
        targetSdk = 35
        versionCode = 325
        versionName = "1.0.0"
    }

    buildTypes {
        // Release ships signed with the debug keystore: every machine builds
        // with its own auto-generated key, so the artifact installs anywhere,
        // while upgrades on this machine keep the established signature.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    // v102 service AAR declares minCompileSdk 37, while this compatibility
    // module intentionally remains on API 36. The two official classes are
    // packaged as jars so only the harmless AAR metadata check is bypassed.
    implementation(files(
        "libs/libxposed-service-102.0.0.jar",
        "libs/libxposed-interface-102.0.0.jar"
    ))
}
