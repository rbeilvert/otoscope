# Otoscope

A FOSS Android app for cheap Wi-Fi otoscope cameras. Reverse-engineered drop-in replacement for the proprietary companion apps these cameras ship with, with none of their ad SDKs, analytics, or trackers.

- Discovers the camera over Bluetooth Low Energy.
- Joins the camera's Wi-Fi access point in an isolated, process-bound network — no impact on your saved Wi-Fi config.
- Streams live video over the camera's UDP protocol (pure Kotlin, no proprietary `.so`, no FFmpeg dependency).
- Auto-rotates the image using the camera's on-board accelerometer / gyro, clipped to a circular mask matching the otoscope lens.
- Horizontal-mirror toggle for self-examination.

## Hardware compatibility

The app supports two camera families. The hardware is identified by its BLE advertisement, and the right protocol is selected automatically.

| Family | SSID prefix | Wi-Fi auth | Companion app | Status |
| ------ | ----------- | ---------- | ------------- | ------ |
| Wudaopu / Xylla | `Enjoy-XXXXXX`, `JesHome-XXXX` | open | "AIR-Look" (`com.air.airlook`) | working |
| Shenzhen Jiding / JEGOAT | `softish-XXXXXX` | WPA2 | "EarVision" (`com.atomath.wifi_camera`) | working |

If you have a Wi-Fi otoscope that pairs with one of those apps but isn't picked up by Otoscope,
please file an issue including preferably the original companion application ID,
a screenshot of the BLE advertisement (e.g. from nRF Connect) and any scan/connect logs from `adb logcat`.

## Build

Requires Android Studio Ladybug+ or a CLI Android SDK with API 35 + JDK 17.

```bash
# Bootstrap the Gradle wrapper once. If you have any modern Gradle:
gradle wrapper --gradle-version 8.10.2 --distribution-type bin

# Debug build (no signing required):
./gradlew assembleDebug

# Install on a connected device:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```


## Status

| Feature                | State |
| ---------------------- | ----- |
| BLE discovery          | done — multi-vendor |
| Wi-Fi join (open or WPA2) | done |
| Live video             | done — MJPEG decoded in-app |
| Auto-rotate + circular mask | done — driven by camera accelerometer / gyro |
| Horizontal-mirror toggle | done — for self-examination |
| Battery + model readout | done — where the camera exposes it |
| Photo / video capture  | not implemented yet |
| Brightness (PWM) control | not implemented yet |

## License

[GPL-3.0-or-later](LICENSE). The project is independent of and not affiliated with Xylla, Wudaopu, Shenzhen Jiding, or any of the brands behind the proprietary companion apps. All trademark references are nominative.
