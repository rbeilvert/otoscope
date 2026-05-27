# Changelog

All notable changes to this project will be documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] — 2026-05-27

Initial release.

- BLE discovery of paired Wi-Fi otoscope cameras (matches the AIR-Look pairing format).
- Joins the camera's open Wi-Fi access point with `WifiNetworkSpecifier`, no permanent Wi-Fi config change.
- Live MJPEG video stream over the camera's custom UDP protocol — pure Kotlin, no proprietary native libraries.
- Auto-rotation driven by the camera's on-board accelerometer (180° offset corrects the lens mounting on Wudaopu hardware).
- Circular lens mask.

