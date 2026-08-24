<h1 align="center">
<a href="https://github.com/rbeilvert/otoscope/"><img align="center" src="fastlane/metadata/android/en-US/images/icon.png" alt="otoscope" width="100" /></a><br>
Otoscope
</h1>

<p align="center">
    A FOSS Android app for cheap Wi-Fi otoscope cameras.
</p>

---

Otoscope is a reverse-engineered drop-in replacement for the proprietary companion apps these cameras ship with, with none of their ad SDKs, analytics, or trackers.

- Discovers the camera either over Bluetooth Low Energy or by scanning nearby Wi-Fi, depending on the model.
- Joins the camera's Wi-Fi access point in an isolated, process-bound network. No impact on your saved Wi-Fi config.
- Streams live video over the camera's native protocol — UDP or RTSP MJPEG — decoded in pure Kotlin. No proprietary `.so`, no FFmpeg dependency.
- Auto-rotates the image using the camera's on-board accelerometer / gyro, clipped to a circular mask matching the otoscope lens.
- Horizontal-mirror toggle for self-examination.
- Ring-light on/off toggle and charging indicator on models that expose them.
- Photo and video capture, written straight to `Pictures/Otoscope` and `Movies/Otoscope`, with an in-app gallery to review, share and delete them.
- Optional free-text caption (e.g. "Left ear") burnt into the corner of every capture.

## Hardware compatibility

The app supports four camera families across two discovery paths. Pick your family on the home screen; the app scans the right way and hands the video off to the vendor-specific protocol automatically.

| Family        | Discovery | SSID prefix                      | Wi-Fi auth | Video | Companion app |
|---------------|-----------|----------------------------------| ---------- | ----- | ------------- |
| **Xylla**     | BLE       | `Enjoy-XXXXXX`<br>`JesHome-XXXX` | open | UDP/8032 MJPEG | "AIR-Look" (`com.air.airlook`) |
| **iTiMO**     | BLE       | `iTiMO-XXXXXX`<br>`jetion_XXXX`    | open | UDP/8031 MJPEG | "iTiMO" (`com.molink.john.itimo`) |
| **JEGOAT**    | BLE       | `softish-XXXXXX`                 | WPA2 | UDP/61501 MJPEG | "EarVision" (`com.atomath.wifi_camera`) |
| **EarFairy**  | Wi-Fi     | `Cooleer_XXXXXX`                 | open | RTSP/7070 MJPEG | "Cooleer" (`com.cooingdv.cooleer`) |

If you have a wireless otoscope that isn't picked up by Otoscope, please file an issue including preferably
the original companion application ID, a screenshot of the BLE advertisement (e.g. from nRF Connect),
the Wi-Fi SSID if available and any scan/connect logs from `adb logcat`.

## Build

Requires Android Studio Ladybug+ or a CLI Android SDK with API 37 + JDK 17.

```bash
# Bootstrap the Gradle wrapper once. If you have any modern Gradle:
gradle wrapper --gradle-version 9.6.1 --distribution-type bin

# Debug build (no signing required):
./gradlew assembleDebug

# Install on a connected device:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Status

| Feature                    | State                                   |
| -------------------------- |-----------------------------------------|
| BLE discovery              | ✅ for Xylla, iTiMO, JEAGOAT             |
| Wi-Fi discovery            | ✅ for EarFairy                          |
| Wi-Fi join                 | ✅ open or WPA2                          |
| Live video                 | ✅ MJPEG decoded in-app (UDP or RTSP)    |
| Auto-rotate + circular mask | ✅ driven by camera accelerometer / gyro |
| Horizontal-mirror toggle   | ✅ for self-examination                  |
| Screen stays awake         | ✅ during live stream                    |
| Battery + model readout    | ✅ where the camera exposes it           |
| Photo / video capture      | ✅ JPEG stills and H.264 MP4 clips        |
| Capture gallery            | ✅ in-app review, share and delete       |
| Brightness control    | 🚧 On-Off implemented for EarFairy      |

## License

[GPL-3.0-or-later](LICENSE). The project is independent of and not affiliated with any of the OEMs or companion-app publishers. All trademark references are nominative.
