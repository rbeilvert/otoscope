package dev.rubec.otoscope.stream.beken

import dev.rubec.otoscope.vendor.Ne3Vendor
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BekenProtocolTest {

    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test fun `sensor axes are signed bytes with noise between them and read about 1 g at rest`() {
        // A real packet from a still camera: x y z at offsets 0, 2, 4; angle -87 at 18.
        val packet = hex("ff 0b fa 07 c2 09") + ByteArray(12) + hex("ff a9") + ByteArray(4)
        assertEquals(24, packet.size)
        val (x, y, z) = requireNotNull(BekenProtocol.parseSensor(packet))
        assertEquals(Triple(-1, -6, -62), Triple(x, y, z))
        assertEquals(-87, BekenProtocol.parseAngle(packet))
        val g = sqrt((x * x + y * y + z * z).toDouble()) / BekenProtocol.COUNTS_PER_G
        assertTrue(g in 0.95..1.05, "magnitude $g g")
    }

    @Test fun `runt sensor packets are rejected`() {
        assertNull(BekenProtocol.parseSensor(byteArrayOf(1, 2, 3)))
        assertNull(BekenProtocol.parseAngle(ByteArray(19)))
    }

    @Test fun `rotation is the cameras own angle`() {
        assertEquals(271f, BekenProtocol.rotationDegrees(-89), 0.01f)
        assertEquals(265f, BekenProtocol.rotationDegrees(-95), 0.01f)
        assertEquals(354f, BekenProtocol.rotationDegrees(-6), 0.01f)
        assertEquals(0f, BekenProtocol.rotationDegrees(-360), 0.01f)
    }

    @Test fun `only the Bouffalo subnet uses the Bouffalo session`() {
        assertTrue(Ne3Vendor.isBouffalo("192.168.169.1"))
        assertFalse(Ne3Vendor.isBouffalo("192.168.1.1"))
    }
}
