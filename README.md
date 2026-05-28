# Otoscope

A FOSS Android app for cheap Wi-Fi otoscope cameras. Reverse-engineered drop-in replacement for the proprietary "AIR-Look" app, with none of its ad SDKs or trackers.

- Discovers the camera over Bluetooth Low Energy.
- Joins the camera's open Wi-Fi access point in an isolated, process-bound network — no impact on your normal Wi-Fi.
- Streams live video over the camera's custom UDP protocol (no proprietary `.so`, no FFmpeg dependency).
- Auto-rotates the image using the camera's on-board accelerometer, clipped to a circular mask matching the otoscope lens.

## Hardware compatibility

Cameras that advertise a Wi-Fi access point named `Enjoy-XXXXXX` or `JesHome-XXXX` and pair with the AIR-Look app should work. Confirmed:

| Brand / SSID prefix | Manufacturer | Status |
| ------------------- | ------------ | ------ |
| `Enjoy-XXXXXX`      | Wudaopu / Xylla | working |

The BLE advert format (manufacturer-data magic `0x66 0x99` followed by the Wi-Fi BSSID) and the on-the-wire camera protocol (`UDP/8032` cmd channel with `0x99 0x99` magic, 24-byte chunk header with `0x66` magic carrying MJPEG payload) are reverse-engineered from a packet capture and the closed-source `libmlcamera-2.5.so`. If you have a similar otoscope that doesn't pair, please file an issue with a screenshot of the BLE advert (e.g. from nRF Connect) and the scan/connect logs.

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

For signed release builds, see [`docs/RELEASING.md`](docs/RELEASING.md).

## Status

| Feature                | State |
| ---------------------- | ----- |
| BLE discovery          | done  |
| Wi-Fi join (open AP)   | done  |
| Live video             | done — MJPEG decoded in-app |
| Auto-rotate + circular mask | done — driven by camera accelerometer |
| Image flip (self-inspection) | done — horizontal mirror toggle with rotation correction |
| Photo / video capture  | not implemented yet |
| Brightness (PWM) control | not implemented yet |

## License

[GPL-3.0-or-later](LICENSE). The project is independent of and not affiliated with Xylla, Wudaopu, or any of the brands behind the "AIR-Look" app. All trademark references are nominative.
