plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// --- Detection model download -------------------------------------------------
// The EfficientDet Lite0 model (~4.5 MB, with metadata) is too large to push
// through the one-file GitHub route used for the rest of the project, so it is
// downloaded once at build time and kept out of git (see .gitignore).
val detectionModelUrl =
    "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite"
val detectionModelFile = file("src/main/assets/efficientdet_lite0.tflite")

tasks.register("downloadDetectionModel") {
    description = "Downloads the EfficientDet Lite0 TFLite model into src/main/assets if missing."
    outputs.file(detectionModelFile)
    onlyIf { !detectionModelFile.exists() }
    doLast {
        detectionModelFile.parentFile.mkdirs()
        logger.lifecycle("Downloading detection model from $detectionModelUrl")
        uri(detectionModelUrl).toURL().openStream().use { input ->
            detectionModelFile.outputStream().use { output -> input.copyTo(output) }
        }
        logger.lifecycle("Detection model saved to $detectionModelFile (${detectionModelFile.length()} bytes)")
    }
}

tasks.named("preBuild") {
    dependsOn("downloadDetectionModel")
}

android {
    namespace = "com.parth.spasha"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.parth.spasha"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // CameraX — live camera preview + frame analysis.
    val cameraXVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    // On-device object detection (TensorFlow Lite Task Library, CPU only).
    // Brings tensorflow-lite + tensorflow-lite-support (TensorImage) with it.
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
}
