import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val hasReleaseSigning = keystorePropsFile.exists()
val keystoreProps = Properties().apply {
    if (hasReleaseSigning) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "es.adeodato.dnsguardian"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "es.adeodato.dnsguardian"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Solo se crea si keystore.properties existe de verdad -- asi
        // assembleDebug/installDebug no dependen en absoluto del keystore
        // de release. Sin cast forzoso a String: si el fichero existe pero
        // le falta alguna clave, el error dice exactamente cual falta, en
        // vez de un ClassCastException generico en fase de configuracion.
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(
                    keystoreProps.getProperty("storeFile")
                        ?: error("keystore.properties: falta la clave 'storeFile'")
                )
                storePassword = keystoreProps.getProperty("storePassword")
                    ?: error("keystore.properties: falta la clave 'storePassword'")
                keyAlias = keystoreProps.getProperty("keyAlias")
                    ?: error("keystore.properties: falta la clave 'keyAlias'")
                keyPassword = keystoreProps.getProperty("keyPassword")
                    ?: error("keystore.properties: falta la clave 'keyPassword'")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Si falta keystore.properties, esta rama simplemente no firma
            // aqui -- el error explicito para quien de verdad pida un
            // build de release vive mas abajo (gradle.taskGraph.whenReady),
            // fuera de la fase de configuracion para no romper clean/debug.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// Falla explicita y clara si de verdad se pide un build/instalacion de
// release sin keystore.properties -- se evalua cuando el grafo de tareas
// ya esta cerrado (whenReady), asi que nunca afecta a clean/assembleDebug/
// installDebug, solo a tareas cuyo nombre empiece por assemble/bundle/
// install y contenga "Release".
if (!hasReleaseSigning) {
    gradle.taskGraph.whenReady {
        val pideRelease = allTasks.any { task ->
            task.name.contains("Release") &&
                (task.name.startsWith("assemble") || task.name.startsWith("bundle") || task.name.startsWith("install"))
        }
        if (pideRelease) {
            throw GradleException(
                "Falta keystore.properties en la raiz del proyecto -- necesario para " +
                    "firmar el build de release (claves esperadas: storeFile, storePassword, " +
                    "keyAlias, keyPassword)."
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}