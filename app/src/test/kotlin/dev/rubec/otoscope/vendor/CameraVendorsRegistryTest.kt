package dev.rubec.otoscope.vendor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Invariant checks on [CameraVendors]. These are non-regression guards on
 * the two things that break subtly when new vendors are added:
 *
 *   - Every registered vendor is reachable via the right discovery-mode filter.
 *   - The dispatch order in [CameraVendors.parseAdvert] hands iTiMO its
 *     advert *before* Xylla, because both share the same 66-99 magic bytes.
 *     If a future contributor reshuffles the list alphabetically, iTiMO's
 *     cameras would be misclassified as Xylla and connect on the wrong UDP
 *     port. This test fails the moment that happens.
 */
class CameraVendorsRegistryTest {

    @Test fun `all vendors are present and split correctly by discovery mode`() {
        assertTrue(XyllaVendor in CameraVendors.all)
        assertTrue(ItimoVendor in CameraVendors.all)
        assertTrue(JegoatVendor in CameraVendors.all)
        assertTrue(EarFairyVendor in CameraVendors.all)

        assertTrue(XyllaVendor in CameraVendors.bleVendors)
        assertTrue(ItimoVendor in CameraVendors.bleVendors)
        assertTrue(JegoatVendor in CameraVendors.bleVendors)
        assertTrue(XyllaVendor !in CameraVendors.wifiScanVendors)

        assertTrue(EarFairyVendor in CameraVendors.wifiScanVendors)
        assertTrue(EarFairyVendor !in CameraVendors.bleVendors)
    }

    @Test fun `iTiMO advert wins over Xylla even with matching 66-99 magic`() {
        // Same 66-99 magic Xylla would accept, but with a name iTiMO also
        // claims. Registry order MUST hand this to iTiMO.
        val record = byteArrayOf(
            0x09, 0xFF.toByte(),
            0x66, 0x99.toByte(),
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66,
        )
        val advert = CameraVendors.parseAdvert(
            bleAddress = "AA:BB",
            deviceName = "iTiMO-04C8BD",
            scanRecord = record,
            rssi = -50,
        )
        assertNotNull(advert)
        assertSame(ItimoVendor, advert.vendor)
    }

    @Test fun `Xylla name still routes to Xylla when iTiMO refuses`() {
        val record = byteArrayOf(
            0x09, 0xFF.toByte(),
            0x66, 0x99.toByte(),
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66,
        )
        val advert = CameraVendors.parseAdvert(
            bleAddress = "AA:BB",
            deviceName = "JesHome-04C8BD", // not iTiMO / jetion
            scanRecord = record,
            rssi = -50,
        )
        assertNotNull(advert)
        assertSame(XyllaVendor, advert.vendor)
    }

    @Test fun `unknown advert returns null instead of a phantom match`() {
        val record = byteArrayOf(0x02, 0x01, 0x06) // just a flags AD
        assertNull(
            CameraVendors.parseAdvert("AA:BB", "SomeoneElseCamera", record, -70)
        )
    }

    @Test fun `Wi-Fi scan dispatch reaches EarFairy on Cooleer_ SSIDs`() {
        val advert = CameraVendors.parseSsid("Cooleer_ABCDEF", "aa:bb:cc:dd:ee:ff", -30)
        assertNotNull(advert)
        assertSame(EarFairyVendor, advert.vendor)
    }

    @Test fun `Wi-Fi scan dispatch returns null on non-camera SSIDs`() {
        assertNull(CameraVendors.parseSsid("HomeNet", "aa:bb", -40))
    }

    @Test fun `vendor display names are unique so the model picker doesn't collide`() {
        val names = CameraVendors.all.map { it.displayName }
        assertEquals(names.size, names.toSet().size, "duplicate displayName in $names")
    }
}
