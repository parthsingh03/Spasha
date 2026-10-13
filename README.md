# SPASHA

SPASHA (spaśa, स्पश) is an Android app that turns your phone camera into a
live tracking view. Point it at a street, a gate, a parking lot — every
person, vehicle and bag in frame gets a box, a track ID, a trail and a
timer. You can pin one track as a Person of Interest, draw a zone on the
screen and get alerted when something crosses into it, and measure vehicle
speed once you calibrate two lines on the ground.

The name is a Rigvedic Sanskrit word. Varuna, the all-seeing god, watched
the world through his *spaśa* — his eyes. The word comes from the root for
"to see", and later it came to mean "spy".

No account, no server, no cloud. The camera work happens entirely on the
phone.

## What it does

**LIVE tab**
- Live camera view with a tracking overlay: bounding boxes, per-type track
  IDs (PERSON #03, CAR #07), confidence, and a fading trail behind each
  track.
- Detects people, bicycles, cars, motorcycles, buses, trucks, backpacks,
  handbags, suitcases and dogs. Everything else is ignored.
- Counts at the top: people in frame, vehicles in frame, total live tracks,
  plus camera FPS and a live clock.
- Tap any track to inspect it: dwell time, first-seen time, speed (if
  calibrated), and a flag score.
- **Flag score** — rule-based and fully printed, never a hidden judgement:
  dwell over 30s (+20), dwell over 60s (+20 more), entered your drawn zone
  (+40), vehicle speed over the limit you set (+30). Capped at 100. The
  panel lists every rule that fired.
- **Pin as POI** — the pinned track turns red, its entries and exits from
  your zone are logged, and the phone vibrates when it enters the zone.
- **Zone** — tap ZONE, tap at least 3 points on the view, press DONE.
  Tracks crossing in or out are logged.
- **Vehicle speed (CAL)** — press CAL, set the real distance on the ground
  between line A (35% height) and line B (65% height) and a speed limit.
  When a vehicle crosses both lines, the app shows its speed in km/h and
  flags it if it is over your limit.

**DOSSIERS tab**
- A list of people currently on the FBI Wanted public list, fetched from
  the FBI's public API. Tap a name for the details from the listing, with a
  button to open the full FBI page.
- This tab is separate from the camera. Nothing in it is ever matched
  against a person in the camera view.

**LOG tab**
- Every event of the session with timestamps: new tracks, POI pins, zone
  entries and exits, 60-second loiters, speed readings, zone and
  calibration changes. Held in memory only — close the app and it is gone.

## How it works

- CameraX feeds frames to EfficientDet Lite0, a small TensorFlow Lite
  object detection model that runs on the phone itself. Frames are never
  uploaded anywhere.
- A simple tracker matches each detection to the track from the previous
  frames (same type, overlapping box or small centre movement) so IDs stay
  stable while a subject is in view.
- The FBI list is fetched over HTTPS from `api.fbi.gov` when you open the
  Dossiers tab. That is the only network call the app makes.

## Permissions

- **CAMERA** — the live view. Detection runs on-device; no video leaves
  the phone.
- **INTERNET** — only for the Dossiers tab (FBI Wanted list).
- **VIBRATE** — a short buzz when a pinned POI enters your zone.

## Building the APK

The easy route is GitHub Actions:

1. Create a GitHub repo and push this whole project to it (including the
   hidden `.github` folder).
2. Add the workflow file by hand if your push route cannot send it:
   `.github/workflows/build-apk.yml`.
3. Open the repo's **Actions** tab — the "Build APK" run builds a debug
   APK and attaches it as an artifact named `SPASHA-debug-apk`.
4. Download the artifact, unzip it, and install `app-debug.apk` on the
   phone (allow "install from unknown sources" for your browser/files app).

The detection model is not in the repo. Gradle downloads it automatically
during the build (task `downloadDetectionModel` in `app/build.gradle.kts`,
wired into `preBuild`). If you build somewhere without internet, download
the EfficientDet Lite0 model by hand and place it at
`app/src/main/assets/efficientdet_lite0.tflite` — the link is in
`app/src/main/assets/README-model.txt`.

Local build, if you have the Android SDK installed: `./gradlew assembleDebug`.

## Limitations — the honest list

- **No face identification.** The app detects that a person is there; it
  never tries to say who they are. There is no face matching of any kind.
- **No number-plate owner lookup.** It does not read plates, and it cannot
  show a vehicle owner's name or details — that data is not public.
- **Track IDs last only while the subject stays in view.** If a person
  walks out and comes back a minute later, they get a new ID. The app does
  not recognise people across time or across cameras.
- **Vehicle speed only works with calibration**, with the phone held
  still, and only when a vehicle crosses both lines. Without calibration
  the app says "NOT CALIBRATED" instead of guessing a number.
- **The flag score is simple rules, not a threat judgement.** It counts
  dwell time, zone entries and speed — nothing more. Read the printed rules
  before you read the number.
- **The Dossiers tab is not linked to live tracks.** It shows the public
  FBI Wanted list. Listings are allegations, not convictions.
- Detection is a small on-device model: expect misses in bad light, at
  long distance, and with heavy overlap. Continuous detection also uses
  battery and can warm the phone.

## Planned work

- Record and export a session log (and clips) to a file.
- A counting line for vehicles and people crossing in each direction.
- Let the user save more than one zone.
- Optional license-plate text reading for vehicles in frame (the plate
  characters only — still no owner lookup).

## Author

Parth Singh
