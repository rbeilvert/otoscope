# HND NE3 Wi-Fi otoscope protocol (Beken BK7231U, "TAIXIN MJPEG")

Reverse-engineered from the **HND** app 1.0.51 (`com.xiaozhen.beauty.hnd`, `V1.0.51.250916`): the classes `T0.d` (`BekenTxinSdk`), `T0.d$a` (sensor thread) and `T0.g` (angle), read with `dexdump` and checked against live traffic. **Status: verified 2026-10-30 on a "HND NE3-d"** (SSID `HNDEC_55-068A52`, brand `HND-NE3`, SoC `BK7231UQFN40`, firmware `1.0.0`): device info, sensor data and 480×480 MJPEG at ~12 fps. `tools/beken_probe.py` is a stdlib-only test client.

## Not the Bouffalo "NE3"

Two different cameras use the SSID prefix `HNDEC_`:

| | this document | Bouffalo BL602 ("NE3", haxko's NE3-Scope) |
|---|---|---|
| camera IP | `192.168.1.1` (DHCP gives the client `192.168.1.x`) | `192.168.169.1` |
| video | UDP 44506, JPEG with its own header | UDP 8800, headerless MJPEG, needs ACK/request-next |
| UDP 8800 | closed: `ICMP port unreachable` | open |

The HND app ships both: `libbl_vii_jni.so` (Bouffalo "VII", port `"8800"` is a string constant in the library) and the pure-Java Beken client described here. It appears to pick one from a `soc_type` value (`SocOnLineChecker` runs first). We did not have a Bouffalo camera to test: the idea is to ask for the device info first (below) and fall back to the Bouffalo protocol when nothing answers.

## Transport

The camera is an open access point and a DHCP server (`192.168.1.1`, hands out `192.168.1.100` and up). It answers **to the source port the request came from**. The vendor app binds the video and sensor sockets to the same port numbers it sends to (44506, 52219) and so do we.

| port | request | reply |
|---|---|---|
| UDP **46526** | `66 30 01 01` | device info, a JSON text, about 500 bytes |
| UDP **52219** | `86 06 01` | starts sensor packets, 24 bytes each, ~12 per second, from 52219 to the sender |
| UDP **44506** | `20 36` | starts the video, chunks from 44506 to the sender |
| UDP **44506** | `20 37` | stops the video |
| TCP 2271 | | a gyro server the app also connects to; not needed |

The vendor app sends the device-info request to the broadcast address `192.168.1.255`; a unicast to `192.168.1.1` works too. The camera also answers the literal text `hello` on 46526.

There is no heartbeat for the Beken client. The vendor app restarts the stream when no data arrives for a while, we send `20 36` again after one second without video. The camera serves one client at a time.

## Device info

```json
{ "uuid": "3F0FFFFF0F0FFFFF02FFFC584A5130F2", "factory": "False", "model": "EAR_CAMERA",
  "brand": "HND-NE3", "soc": "BK7231UQFN40", "hardware": "1.0.1", "firmware": "1.0.0",
  "bat_capacity": "250mAh", "bat_life": "[3,2,1]", "macid": "FC584A5130F2",
  "calibrated": "0", "cam_brightness": "50", "wifi_mode": "AP", "imu_axis": "3",
  "designed_by": "C-Chip Technology Co., Ltd", "manufacturer": "C-Chip Technology Group", ... }
```

No battery percentage was found. `bat_life` (`[3,2,1]`) and `bat_capacity` (250 mAh) are static strings in the reply; what `bat_life` means is unknown, so the apps show no battery for this camera.

## Video packet

Datagrams up to 1472 bytes. A 4-byte header, then a slice of one complete JPEG (JFIF, the first chunk starts with `FF D8`):

```
off size
 0   1   frame id, +1 per frame, wraps at 256
 1   1   1 on the last chunk of the frame, else 0
 2   1   chunk number, starting at 1; on the last chunk it equals the chunk count
 3   1   4 in every capture so far
 4   …   JPEG bytes
```

The vendor app keeps three frame slots (`frame id % 3`), puts each chunk at its number and joins the chunks when all are there. Chunks can arrive twice; a repeated chunk of a finished frame must be ignored. The image is **480×480**, baseline JPEG, around 8 KB.

## Sensor packet

24 bytes, about 12 per second, only while the video runs:

```
off size
 0   1   x, signed 8 bit   along the lens axis (stays near 0 with the camera horizontal)
 1   1   noise: a random nibble 0..15, ignore
 2   1   y, signed 8 bit   in the picture plane
 3   1   noise
 4   1   z, signed 8 bit   in the picture plane
 5   1   noise
 6  12   0
18   2   roll angle in degrees, signed 16 bit BIG endian, always in [-360, 0)
20   4   0
```

The accelerometer reads **64 counts per g**: over 400 packets of a hand-held camera `|(x,y,z)|` was 63.1 ± 3.6, and `x` stayed within -1 ± 2. A camera lying with gravity along -z reads `(-1, -6, -62)`.

The camera computes the roll angle itself (offset 18). It follows `atan2(z, -y) + 2.6°` (circular fit over the same packets, R = 0.978, no time lag), so it is `y`/`z` turned into degrees, wrapped into [-360, 0). The vendor app uses this value directly (`T0.g.b` reads those two bytes as a signed short) and adds a mode-dependent offset.

The apps use the angle as the picture rotation unchanged: `angle mod 360` (`rotation_degrees` in `otoscope_win/beken.py`, `BekenProtocol.rotationDegrees` in the Android app). A first guess with an extra half turn (`angle + 180`) showed the picture upside down on a phone; without it the picture was upright in the pose that was tried (camera angle about -90 degrees, lens horizontal, the camera held as for an ear). The green tip is always at the left edge of the picture, so the picture is fixed to the camera and has to be turned by the roll. Other roll angles were not compared systematically against the picture.

## Vendor app internals (for reference)

`T0.d.o()` is the whole session: send `66 30 01 01` twice to `192.168.1.255:46526`, send `86 06 01` to `<camera>:52219` from a socket bound to 52219 and start the sensor thread, send `20 36` to `<camera>:44506` from a socket bound to 44506, then loop on `receive` and call `n(data, len)` per datagram. Stop sends `20 37`. `T0.d.F/G/D/E` are the four byte arrays above.
