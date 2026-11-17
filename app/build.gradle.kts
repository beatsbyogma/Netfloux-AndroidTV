plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cinetv"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.cinetv"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "2.0"

        // Le moteur VLC existe pour toutes les puces ; une TV n'a besoin que des versions ARM
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            // Version finale, pour un usage au quotidien : nettement plus fluide
            // que la version « debug ». Signée avec la clé de développement,
            // ce qui permet de l'installer par-dessus sans rien perdre.
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    // La bibliothèque réseau embarque des fichiers de licence en double
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }

    // Elle référence aussi des classes absentes d'Android, jamais appelées ici :
    // inutile que le contrôle de la version finale s'y arrête.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// Interrupteur « version finale uniquement ».
// Dans gradle.properties : netfloux.releaseOnly=true  → Android Studio n'installe
// que la version release, la plus fluide. Remettre false pour retrouver la
// version debug, nécessaire à Live Edit (réglage du design en direct).
val releaseOnly = providers.gradleProperty("netfloux.releaseOnly").orNull == "true"

androidComponents {
    beforeVariants(selector().withBuildType("debug")) { variant ->
        if (releaseOnly) variant.enable = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Compose for TV
    implementation("androidx.tv:tv-material:1.1.0")

    // Socle Android
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.documentfile:documentfile:1.1.0")

    // Lecture vidéo
    // Moteur de lecture : celui de VLC (ouvre et décode tous les formats)
    implementation("org.videolan.android:libvlc-all:3.6.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("androidx.media3:media3-datasource:1.11.1")

    // Dossiers partagés sur le réseau local (partages Windows, NAS)
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")

    // Chargement d'images
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
