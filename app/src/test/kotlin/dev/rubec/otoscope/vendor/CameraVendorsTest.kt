package dev.rubec.otoscope.vendor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SSID recognition goes through [CameraVendors.parseSsid] for every Wi-Fi
 * scan result. These tests fence the SSID patterns of each Wi-Fi-discoverable
 * family plus the no-match cases the scanner relies on staying quiet about.
 *
 * The split between [XyllaVendor] (BLE, `Enjoy-` / `JesHome-`) and
 * [Y38Vendor] (Wi-Fi, `AIR-ES-`) is deliberate: they share the UDP/8032 MJPEG
 * wire protocol but are sold under different brands and reached through
 * different discovery paths, so a user who bought a Y38 sees "Y38" on the
 * home screen, not "Xylla".
 */
class CameraVendorsTest {

    @Test fun `AIR-ES SSID is discovered as Y38 over Wi-Fi`() {
        val advert = CameraVendors.parseSsid("AIR-ES-4d3418", "C4:68:68:4D:34:18", -56)
        assertEquals(Y38Vendor, advert?.vendor)
        assertEquals("AIR-ES-4d3418", advert?.ssid)
        assertEquals("C4:68:68:4D:34:18", advert?.bssid)
        // The AP is open — nothing to store, nothing to derive.
        assertEquals(null, advert?.wpa2Passphrase)
    }

    @Test fun `AIR-ES SSID match is case-insensitive and accepts the dashless spelling`() {
        assertEquals(Y38Vendor, CameraVendors.parseSsid("air-es6a78c8", "44:94:A6:6A:78:C8", -60)?.vendor)
        assertEquals(Y38Vendor, CameraVendors.parseSsid("AIR-ES6A78C8", "44:94:A6:6A:78:C8", -60)?.vendor)
    }

    @Test fun `Cooleer SSID still routes to EarFairy`() {
        assertEquals(EarFairyVendor, CameraVendors.parseSsid("Cooleer_123456", "AA:BB:CC:DD:EE:00", -55)?.vendor)
    }

    @Test fun `Soulear SSID still routes to i4season`() {
        assertEquals(I4seasonVendor, CameraVendors.parseSsid("Soulear-abc12", "AA:BB:CC:DD:EE:01", -55)?.vendor)
    }

    @Test fun `unrelated SSID matches no vendor`() {
        assertEquals(null, CameraVendors.parseSsid("Kekeno", "22:7C:8F:46:0A:27", -26))
    }

    @Test fun `generic AIR-ES-prefixed home networks are not cameras`() {
        // A false hit is more than cosmetic: a listed SSID can be joined, and
        // an already-joined false positive would be adopted as a camera
        // network, binding all of the app's traffic to it. The pattern must
        // demand the firmware's device-tag shape, not a loose prefix.
        assertEquals(null, CameraVendors.parseSsid("AIR-ES", "22:7C:8F:46:0A:27", -40))
        assertEquals(null, CameraVendors.parseSsid("AIR-ES-", "22:7C:8F:46:0A:27", -40))
        assertEquals(null, CameraVendors.parseSsid("AIR-ESX", "22:7C:8F:46:0A:27", -40))
    }

    @Test fun `BLE-only families do not claim SSIDs on the Wi-Fi path`() {
        // Xylla, iTiMO and JEGOAT ride the BLE scan to produce their advert
        // (passphrase / port overrides / GATT handshake all travel with it),
        // so their SSIDs intentionally stay off the Wi-Fi-scan dispatch even
        // though some of them look otoscope-shaped.
        assertEquals(null, CameraVendors.parseSsid("Enjoy-ABC123", "AA:BB:CC:DD:EE:FF", -50))
        assertEquals(null, CameraVendors.parseSsid("JesHome-1234", "AA:BB:CC:DD:EE:FF", -50))
        assertEquals(null, CameraVendors.parseSsid("iTiMO-ABC123", "AA:BB:CC:DD:EE:01", -50))
        assertEquals(null, CameraVendors.parseSsid("softish-ABCDEF", "AA:BB:CC:DD:EE:02", -50))
    }

    @Test fun `Xylla and Y38 sit on opposite discovery cards`() {
        assertTrue(XyllaVendor in CameraVendors.bleVendors)
        assertFalse(XyllaVendor in CameraVendors.wifiScanVendors)

        assertTrue(Y38Vendor in CameraVendors.wifiScanVendors)
        assertFalse(Y38Vendor in CameraVendors.bleVendors)
    }
}
