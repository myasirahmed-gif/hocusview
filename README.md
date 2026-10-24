# Hocus Vision

An Android camera HUD prototype with local object detection, selectable targets,
approximate box-overlap tracking, and camera zoom controls.

## Build

Open this folder in Android Studio. Install Android SDK Platform 35 and use a JDK
supported by Gradle 9.6 (Android Studio's bundled JDK works). Then run:

```powershell
.\gradlew.bat assembleDebug lintDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Use

Allow camera access, point at a common object, and tap its detected box. The HUD
shows the predicted label, confidence, and a temporary tracking ID. The zoom slider
uses the camera's reported range. Pause stops detection while keeping the preview
live. Detection runs on the CPU; frames are dropped while analysis is busy.

## Compatibility and limits

- Minimum Android API 23 (Android 6), with a camera and a supported native ABI.
- No account, server, Google Play services, or runtime internet permission.
- The model recognizes the 80 COCO object classes; it cannot identify everything,
  identify people by name, or provide arbitrary product specifications.
- IDs use overlap matching, so rapid movement or crossing objects can change IDs.
- Distance estimation, gaze input, automatic target zoom, speech commands, and
  detailed AI descriptions are not implemented.
- Front/rear simultaneous camera use and reliable gaze tracking are device-specific.
- Real devices, including Android devices with 16 KB memory pages, still require
  compatibility testing. A minimum SDK setting is not a universal-device guarantee.

## Model and dependencies

The bundled EfficientDet-Lite0 quantized model comes from Google's TensorFlow
Lite model distribution, with built-in detection postprocessing:

https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite

Model documentation and supported classes:
https://github.com/tensorflow/examples/tree/master/lite/examples/object_detection/android

Bundled model SHA-256:
`2e04c53bfeac0ac2a30c057c7e2a777594ce39baaac35a92f74fb1e8c4fc4e0b`

Inference uses LiteRT Interpreter 1.4.2. Camera access uses AndroidX
CameraX 1.4.2. Review the upstream licenses before distributing a release.

## Model verification

`tools/verify_model.py` checks the same UINT8 RGB input and four detection outputs
used by the Android decoder. Development dependencies are `ai-edge-litert`, `numpy`,
and `Pillow`. Run against a known sample with:

```powershell
python tools/verify_model.py sample.jpg --expect dog
```

The TensorFlow Yellow Labrador sample passed with a dog detection at 0.840
confidence. This verifies model inference and decoding on the development host;
physical-device camera and performance testing is still required.
