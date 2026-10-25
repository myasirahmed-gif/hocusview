# Hocus Vision

An immersive Android vision HUD with compact controls, local object recognition,
tracking, supervised visual memory, night assist, vehicle OCR, and optional Gemini snapshots.

## Build

Open this folder in Android Studio. Install Android SDK Platform 35 and use a JDK
supported by Gradle 9.6 (Android Studio's bundled JDK works). Then run:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug --no-problems-report
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Version 0.3 controls and Gemini

The default dock is compact (roughly 95 dp tall): **Lock**, **Analyze**, **Path**,
and **Tools**. Zoom, Teach, Library, and assisted-learning controls expand under Tools.
Night and Lamp are separate small controls on the camera view. The compact layout
is checked at 360 dp width; native-rendered previews are under `app/build/reports/screenshots`.

Open **SET** to enter your own Gemini API key. Choose a model ID or use **Test / Models**
to list models available to your key. Availability does not guarantee a free quota.
The default is `gemini-3.1-flash-lite`; it can be changed without rebuilding the APK.
The API key is encrypted with AES-GCM using an Android Keystore key, never embedded
in the APK or source, and hidden from screenshots while Settings is open. Remove
Saved Key deletes the locally stored credential. Each installation uses its own key.

Analyze uploads only the requested selected crop (or a full frame if no visible
object is selected). Path uploads a full snapshot. Both require upload consent.
Requests are user-triggered, run off the UI thread, and limited to one at a time;
there is no continuous video upload or automatic retry. Failed cloud requests do
not disable local tracking. Images are not written to an analysis history.

Protocol documentation: https://ai.google.dev/gemini-api/docs/image-understanding

## Night assist and path snapshots

Night enables a modest preview brightness adjustment and attempts CameraX native
low-light boost when supported. Otherwise it uses supported exposure compensation.
Lamp switches the camera torch on devices that have one. Torch and native boost
are mutually exclusive. Night snapshots can receive gamma enhancement, but true
black frames remain black; this is not infrared or thermal vision.

Path requires Gemini, adequate visible light, and a fresh frame. Very dark frames
are blocked with a prompt to stop and turn on a light. The returned dialog shows
the **exact analyzed snapshot**, its age, a possible floor polygon, possible stairs/
steps/drop-offs/obstacles, and a relative image-sector sketch. Coordinates and
confidence values are validated. Low/unknown visibility discards all geometry.

This is a stop-and-inspect prototype, **not validated walking or driving navigation**,
a 3D floor plan, or a mapped route. It never marks a path safe/clear, gives walking
or turning instructions, or overlays an old cloud result onto the current live
camera. Path summaries and caution text are derived locally from validated fields,
so a model-written "walk forward" instruction is not surfaced. Obstacles can be
missed even when the snapshot appears well lit.

## Vehicle number-plate popups

Detected cars, motorcycles, trucks, and buses trigger a nonmodal plate-scanning
popup and per-track OCR badges. Bundled Latin OCR examines one vehicle crop at a
time, rotating through vehicles at a bounded rate. It considers single-line and
two-line letter/digit layouts, filters obvious brand/phone/advertisement text, and
requires two separated matching reads for a repeated OCR match. Tap the popup to
dismiss it temporarily. Plate images/numbers are not persisted or sent to Gemini
automatically.

The scan is orientation-agnostic: visible front or rear plates can be read; side
views or obscured/tiny/blurred plates can remain unreadable. This is vehicle-gated
OCR with candidate filtering, not a dedicated trained plate-localization model.
Results remain OCR candidates to verify visually, not verified registrations. There
is no owner lookup. Non-Latin scripts and many regional formats are not covered.
Uncertain plates show "unreadable" or "verifying" instead of an invented number.

## Use

The camera fills the screen, with cutout-aware floating controls and transient
system bars. Tap a detected object to lock its track. A live cropped thumbnail
follows the selected box; the bottom card shows its label and recognition source.
Use Lock, or tap an empty image area, to track an object outside the
detector's classes. Manual locks use local patch matching and can be named with Teach.

The tracker combines one-to-one appearance matching, motion prediction, and
responsive box smoothing. Selected tracks survive detection gaps up to 1.8 seconds;
predicted positions show amber dashed brackets and REACQUIRING. A lost patch is
not treated as a fresh model detection. Camera zoom uses the device's real range.

## 100 built-in categories

`app/src/main/assets/object-catalog.json` contains **100 distinct model-backed
categories**: 80 COCO classes detected throughout the scene, plus 20 ImageNet
categories recognized in a locked crop. The Library marks these AUTO and FOCUS.
The extra categories include helmets, hammer, screwdriver, power drill, wallet,
sunglasses, digital watch, microphone, modem, binoculars, accordion, padlock,
stethoscope, iron, perfume, paintbrush, pencil sharpener, rubber eraser, syringe,
and combination lock. `tools/build_catalog.py` verifies every alias against the
bundled model labels. This is a pretrained recognition catalog, not a new raw
100-class training-photo dataset or a claim of 100-class bounding-box detection.

## Learning new objects

1. Lock an object, hold steady, tap Teach, and provide its name.
2. Save different viewpoints with the same name. Duplicate views are rejected.
3. After three confirmed views, Assist can add sufficiently different, high-match
   views of a stable track. It cannot invent a new label. Turn Assist off to pause it.
4. Open Library > Learned objects to inspect example counts or forget an object.

This is instance-based learning over fixed MobileNet features plus color/spatial
appearance, with similarity and ambiguity rejection. It does **not** retrain neural
network weights or automatically discover the correct names of unfamiliar objects.
Custom recognition runs on the selected crop, rather than searching the entire
camera frame for every learned object. Similar-looking objects can be confused.

Memory supports up to 100 custom names and 12 cropped examples per name. JPEGs and
feature vectors persist in app-private `files/object-memory`, using an atomic JSON
index. Learned examples survive app restart and an APK update; uninstalling or
clearing app data removes them. Samples remain on the device.

## Compatibility and limits

- Minimum Android API 23 (Android 6), with a camera and a supported native ABI.
- Local tracking, visual memory, and bundled OCR work without an account or internet.
- Optional Gemini snapshots require an API key, internet, upload consent, and available quota.
- Recognition is limited by the bundled models and taught examples. It cannot
  identify everything, identify people by name, or provide arbitrary specifications.
- Long occlusions, similar appearances, lighting changes, scale changes, and rapid
  camera motion can still break a lock or change an ID.
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
CameraX 1.5.3. Plate OCR uses bundled ML Kit Latin text recognition 16.0.1.

The bundled MobileNet V1 float classifier comes from:
https://storage.googleapis.com/download.tensorflow.org/models/mobilenet_v1_2018_02_22/mobilenet_v1_1.0_224.tgz

The archive's `mobilenet_v1_1.0_224.tflite` is used with RGB normalization
`(pixel - 127.5) / 127.5` and the matching 1001-entry label list:
https://storage.googleapis.com/download.tensorflow.org/data/ImageNetLabels.txt

MobileNet SHA-256:
`a6e60eb9b50ca82aedbe12f1340b3bfbc57d1e771b71d2f98bfed3cd01e339f9`

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

`tools/verify_focus.py sample.jpg --expect 'military uniform'` checks the MobileNet
input normalization, label alignment, output decoding, and 100-category catalog
against TensorFlow's Grace Hopper sample. JVM tests cover fast movement, temporary
occlusion, crossing targets, manual patch following, crop ownership, learned-memory
persistence and deletion, ambiguity rejection, assisted-learning gates, and UI layout.
