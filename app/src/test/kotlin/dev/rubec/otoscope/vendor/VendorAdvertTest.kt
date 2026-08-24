package dev.rubec.otoscope.vendor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Per-vendor advert-parsing regression tests. Each vendor has a very
 * specific magic-byte + prefix layout it recognises; these tests fence
 * both the shape and the wire → [CameraAdvert] mapping so a refactor
 * can't quietly break discovery for any of the shipped families.
 *
 * We test parseAdvert / parseSsid directly (they're pure functions with no
 * Android dependencies at call time).
 */
class VendorAdvertTest {

    // ---- Xylla ------------------------------------------------------------

    @Test fun `Xylla accepts a valid 66-99 advert and extracts the BSSID`() {
        val bssid = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val record = byteArrayOf(
            0x09, 0xFF.toByte(),
            0x66, 0x99.toByte(),
            *bssid,
        )
        val advert = XyllaVendor.parseAdvert(
            bleAddress = "AA:BB:CC:DD:EE:FF",
            deviceName = "JesHome-04C8BD",
            scanRecord = record,
            rssi = -55,
        )
        val a = requireNotNull(advert)
        assertEquals(XyllaVendor, a.vendor)
        assertEquals("JesHome-04C8BD", a.ssid)
        assertEquals("11:22:33:44:55:66", a.bssid)
        assertNull(a.wpa2Passphrase) // Xylla APs are open
        assertEquals("AA:BB:CC:DD:EE:FF", a.bleAddress)
        assertEquals(-55, a.rssi)
    }

    @Test fun `Xylla rejects advert with wrong magic bytes`() {
        val record = byteArrayOf(
            0x09, 0xFF.toByte(),
            0x99.toByte(), 0x66,                         // reversed magic
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66,
        )
        val advert = XyllaVendor.parseAdvert("AA:BB", "Enjoy-01", record, -50)
        assertNull(advert)
    }

    @Test fun `Xylla rejects payload shorter than 6 BSSID bytes`() {
        val record = byteArrayOf(
            0x06, 0xFF.toByte(),
            0x66, 0x99.toByte(),
            0x11, 0x22, 0x33,                            // only 3 bytes of BSSID
        )
        assertNull(XyllaVendor.parseAdvert("AA:BB", "Enjoy-01", record, -50))
    }

    @Test fun `Xylla rejects blank device name`() {
        val record = byteArrayOf(
            0x09, 0xFF.toByte(),
            0x66, 0x99.toByte(),
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66,
        )
        assertNull(XyllaVendor.parseAdvert("AA:BB", null, record, -50))
        assertNull(XyllaVendor.parseAdvert("AA:BB", "", record, -50))
    }

    // ---- iTiMO -----------------------------------------------------------

    @Test fun `iTiMO accepts iTiMO-prefixed names and jetion-prefixed names`() {
        val bssid = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F)
        val record = byteArrayOf(
            0x09, 0xFF.toByte(),
            0x66, 0x99.toByte(),
            *bssid,
        )
        val a = requireNotNull(
            ItimoVendor.parseAdvert("AA:BB", "iTiMO-123456", record, -60)
        )
        assertEquals("0A:0B:0C:0D:0E:0F", a.bssid)
        assertEquals(ItimoVendor, a.vendor)

        val b = requireNotNull(
            ItimoVendor.parseAdvert("AA:BB", "jetion_1234", record, -60)
        )
        assertEquals(ItimoVendor, b.vendor)
    }

    @Test fun `iTiMO rejects Xylla-branded names even with same magic`() {
        // Same 66-99 envelope as Xylla, but the SSID name doesn't match
        // iTiMO's prefix list — must fall through so Xylla's parser can
        // claim it downstream.
        val record = byteArrayOf(
            0x09, 0xFF.toByte(),
            0x66, 0x99.toByte(),
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66,
        )
        assertNull(ItimoVendor.parseAdvert("AA:BB", "JesHome-XXXX", record, -60))
    }

    // ---- JEGOAT ----------------------------------------------------------

    @Test fun `JEGOAT extracts BSSID and derives WPA2 passphrase from it`() {
        val timestamp = ByteArray(6) { 0 } // 6-byte build timestamp, ignored
        val bssid = byteArrayOf(
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),
            0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
        )
        val record = byteArrayOf(
            0x0F, 0xFF.toByte(),
            0x0F, 0x27,                                          // company ID (LE)
            *timestamp,
            *bssid,
        )
        val a = requireNotNull(
            JegoatVendor.parseAdvert("AA:BB", "softish-04C8BD", record, -40)
        )
        assertEquals("AA:BB:CC:DD:EE:FF", a.bssid)
        // Passphrase is the BSSID in lowercase hex, no separators.
        assertEquals("aabbccddeeff", a.wpa2Passphrase)
    }

    @Test fun `JEGOAT rejects Xylla magic (wrong company ID)`() {
        val record = byteArrayOf(
            0x0F, 0xFF.toByte(),
            0x66, 0x99.toByte(),                                 // Xylla's magic
            0, 0, 0, 0, 0, 0,
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),
            0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
        )
        assertNull(JegoatVendor.parseAdvert("AA:BB", "softish-1234", record, -40))
    }

    // ---- EarFairy (Wi-Fi scan, no BLE) -----------------------------------

    @Test fun `EarFairy matches Cooleer-prefixed SSIDs`() {
        val a = requireNotNull(
            EarFairyVendor.parseSsid("Cooleer_04c8bd", "d8:83:32:bd:c8:04", -30)
        )
        assertEquals(EarFairyVendor, a.vendor)
        assertEquals("Cooleer_04c8bd", a.ssid)
        assertEquals("d8:83:32:bd:c8:04", a.bssid)
        assertNull(a.wpa2Passphrase)
        assertEquals("", a.bleAddress) // no BLE for this family
    }

    @Test fun `EarFairy match is case-insensitive on the prefix`() {
        val a = EarFairyVendor.parseSsid("COOLEER_ABCDEF", "de:ad:be:ef:00:00", -55)
        assertEquals(EarFairyVendor, requireNotNull(a).vendor)
    }

    @Test fun `EarFairy ignores unrelated SSIDs`() {
        assertNull(EarFairyVendor.parseSsid("HomeWiFi-5GHz", "aa:bb:cc:dd:ee:ff", -70))
        assertNull(EarFairyVendor.parseSsid("cooleer", "aa:bb:cc:dd:ee:ff", -70)) // no underscore, no id
    }
}
