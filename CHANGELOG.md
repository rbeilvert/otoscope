# Changelog

All notable changes to this project will be documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.5.0] — 2026-07-10

### Added
- Support for the iTiMO family: cameras advertising as `iTiMO-XXXXXX` and paired with the "iTiMO" companion app (`com.molink.john.itimo`). Shares the Wudaopu wire format with the video/preview channel moved to UDP/8031.

## [0.4.0] — 2026-06-16

### Added
- Screen no longer sleeps while a stream is on-screen. The wake behaviour is scoped to the streaming view, so the device returns to its normal timeout as soon as you disconnect or navigate away. No permission requested, no background wake lock held.

## [0.3.0] — 2026-06-14

### Added
- Support for the Shenzhen Jiding / JEGOAT family — cameras advertising as `softish-XXXXXX`. WPA2-secured Wi-Fi, BLE GATT pre-handshake required before the camera enables its access point, raw-UDP JPEG video stream with per-frame gyro rotation, and JSON telemetry for battery, charging state, and firmware version.
- Multi-vendor architecture. Each camera family is plugged in via a `CameraVendor` strategy (BLE-advert parser, Wi-Fi credential source, optional GATT handshake hook, session factory), so adding future hardware doesn't touch the BLE scanner, Wi-Fi connector, or ViewModel.
- Pairing retry — the BLE knock + Wi-Fi connect now retry up to three times with a short backoff. First-try failures (camera not yet broadcasting its AP) no longer require the user to restart the flow manually.
- Debug-overlay block under the streaming view (debug builds only) surfacing firmware version, stream resolution, frame rate, and Wi-Fi RSSI when the camera reports them.

### Changed
- `CameraWifiConnector` now honours a per-advert WPA2 passphrase when present, falls back to open AP otherwise.
- Camera-status flows (battery, model, frame, rotation) are now exposed through a vendor-agnostic `CameraSession` interface; the ViewModel no longer knows which hardware it's talking to.
- JPEG decoding now uses `android.graphics.ImageDecoder` with the hardware allocator (with a `BitmapFactory` fallback for occasional partial frames), roughly halving the per-frame CPU cost and reducing visible stutter.
- Video frame assembly now uses the per-frame total-chunk count to detect dropped chunks and discard incomplete frames instead of decoding partial JPEGs that produced visible artefacts.

### Fixed
- Wudaopu charging icon never updated when the camera was plugged in. The previous bit-decoding skipped the cable-connected flag entirely on firmware that sets a particular status bit; charging is now parsed unconditionally from the relevant status word.

## [0.2.0] — 2026-06-05

### Added
- "Mirror view" toggle to flip the image horizontally — useful for self-examination. Rotation is inverted automatically so a clockwise hand motion still looks clockwise on screen.
- Top-bar control to turn on Bluetooth or Wi-Fi from the app when either is disabled.

### Fixed
- Image was upside down on Wudaopu hardware. The lens is mounted 180° relative to the accelerometer frame; corrected with a fixed offset.

## [0.1.0] — 2026-05-27

Initial release.

- BLE discovery of paired Wi-Fi otoscope cameras (Wudaopu / Xylla family — `Enjoy-XXXXXX`, `JesHome-XXXX` SSIDs).
- Joins the camera's open Wi-Fi access point with `WifiNetworkSpecifier`, no permanent Wi-Fi config change.
- Live MJPEG video stream over the camera's custom UDP protocol — pure Kotlin, no proprietary native libraries.
- Auto-rotation driven by the camera's on-board accelerometer.
- Circular lens mask.
