package dev.rubec.otoscope.vendor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [Ne3Vendor] parses Wi-Fi scan hits — matching on the `HNDEC_` SSID prefix
 *  and stamping the fixed WPA2 passphrase into every recognised advert. */
class Ne3AdvertTest {

    @Test fun `matches HNDEC prefixed SSIDs and stamps the WPA2 passphrase`() {
        val a = requireNotNull(
            Ne3Vendor.parseSsid("HNDEC_55-ABC123", "aa:bb:cc:dd:ee:ff", -35)
        )
        assertEquals(Ne3Vendor, a.vendor)
        assertEquals("HNDEC_55-ABC123", a.ssid)
        assertEquals("aa:bb:cc:dd:ee:ff", a.bssid)
        assertEquals("1234567890", a.wpa2Passphrase)
        assertEquals("", a.bleAddress)
        assertEquals(-35, a.rssi)
    }

    @Test fun `match is case-insensitive on the prefix`() {
        val a = Ne3Vendor.parseSsid("hndec_99-DEADBE", "11:22:33:44:55:66", -50)
        assertEquals(Ne3Vendor, requireNotNull(a).vendor)
    }

    @Test fun `ignores unrelated SSIDs`() {
        assertNull(Ne3Vendor.parseSsid("HomeWiFi-5GHz", "aa:bb:cc:dd:ee:ff", -70))
        assertNull(Ne3Vendor.parseSsid("hndec", "aa:bb:cc:dd:ee:ff", -70)) // no underscore, no id
        assertNull(Ne3Vendor.parseSsid("Cooleer_abc123", "aa:bb:cc:dd:ee:ff", -70)) // EarFairy's territory
    }
}
