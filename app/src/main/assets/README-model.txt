MODEL FILE — NOT COMMITTED
===========================

The object detection model (efficientdet_lite0.tflite, EfficientDet Lite0
with metadata) is NOT stored in this folder in git. It is about 4.5 MB and
too large for the one-file push route used for this project.

A Gradle task in app/build.gradle.kts (downloadDetectionModel, wired into
preBuild) downloads it automatically on the first build from:

https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite

If the build machine has no internet, download that file by hand and put it
in this folder as: efficientdet_lite0.tflite
