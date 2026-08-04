# Changelog

All notable changes to this project will be documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.6.0] — 2026-08-04

### Added
- Support for **EarFairy** otoscopes (tested with model _Y-201_). These devices have no BLE component. The app scans nearby Wi-Fi and lists any `Cooleer_XXXXXX` access point so you can join with a single tap.
- Live video for EarFairy via RTSP with a self-contained MJPEG-over-RTP (RFC 2435) depacketiser. No FFmpeg, no native code.
- Ring-light on/off toggle for EarFairy, in addition with features already supported by other brands.
- **Home screen model picker**. Two cards (Bluetooth pairing for Xylla / iTiMO / JEGOAT, Wi-Fi scan for EarFairy) list every supported model up-front so you can pick your discovery path easily. Permission prompts are also gated behind this choice: users on Wi-Fi-only hardware don't get asked for Bluetooth permission.

### Changed
- Renamed the "Wudaopu / Xylla" family to "Xylla" and "JEGOAT / EarVision" to "JEGOAT" throughout the app UI and code.


## [0.5.3] — 2026-07-29

### Changed
- Build toolchain moved to Gradle 9.6.1, Android Gradle Plugin 9.3.1, and Kotlin 2.4.10, and the app now compiles against SDK 37. `targetSdk` stays at 35.
- Dependencies refreshed: Compose BOM 2026.06.01, `core-ktx` 1.19.0, Lifecycle 2.11.0, `activity-compose` 1.13.0, coroutines 1.11.0.
- The launcher icon now ships a monochrome layer, so it follows the system theme on launchers that support themed icons.

## [0.5.2] — 2026-07-23

### Added
- iTiMO cameras that advertise as `jetion_XXXX` are now recognised alongside the `iTiMO-XXXXXX` naming. Both spellings are the same hardware family, sold under different branding.

### Fixed
- Active VPNs on the phone used to leave the app stuck on "Waiting for frames". The app now detects the refused socket bind and shows a clear error asking the user to disable their VPN and try again. A general "no video received" safety net also kicks in if packets never arrive for any other reason.
- iTiMO cameras that don't run a DHCP server were unreachable. The fallback camera address is now the vendor's own app hard-coded `192.168.10.123`.

## [0.5.1] — 2026-07-17

### Fixed
- Turning the camera off mid-stream no longer crashes the app. Both the Wi-Fi network-lost callback and a stream-stall watchdog now trigger a graceful teardown that shows a brief "Camera disconnected" notice and returns to the home screen. The previously-discovered camera is also dropped from the list so it can't be tapped for a phantom reconnect.

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
