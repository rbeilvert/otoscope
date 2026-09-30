package dev.rubec.otoscope.stream.i4season

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression tests for the i4season control codec. Reference values are from
 * a Hopefox Find T (`BK7231U-XRH-FBPRO`, firmware `HKV41B`).
 */
class I4seasonProtocolTest {

    @Test fun `request header is little-endian with magic, seq, cmd and length`() {
        val pkt = I4seasonProtocol.request(seq = 0x0102, cmd = I4seasonProtocol.CMD_OPEN_VIDEO, payload = byteArrayOf(9, 8))
        assertContentEquals(
            byteArrayOf(
                0xEE.toByte(), 0xFF.toByte(), 0xEE.toByte(), 0xFF.toByte(),
                0x02, 0x01, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00, 9, 8,
            ),
            pkt,
        )
    }

    @Test fun `open video payload carries pic port, no audio, client id`() {
        val p = I4seasonProtocol.openVideoPayload(picPort = 57087, clientId = 0x11223344)
        assertContentEquals(
            byteArrayOf(0xFF.toByte(), 0xDE.toByte(), 0, 0, 0x44, 0x33, 0x22, 0x11),
            p,
        )
    }

    @Test fun `reply round-trips seq, cmd, status and payload`() {
        val raw = I4seasonProtocol.request(seq = 7, cmd = 1, payload = byteArrayOf(1, 2, 3))
        raw[9] = 3 // status
        val reply = assertNotNull(I4seasonProtocol.parseReply(raw, raw.size))
        assertEquals(7, reply.seq)
        assertEquals(1, reply.cmd)
        assertEquals(3, reply.status)
        assertContentEquals(byteArrayOf(1, 2, 3), reply.payload)
    }

    @Test fun `reply without magic is rejected`() {
        val raw = ByteArray(16)
        assertNull(I4seasonProtocol.parseReply(raw, raw.size))
        assertNull(I4seasonProtocol.parseReply(raw, 4))
    }

    @Test fun `devinfo parses strings and battery from the Find T layout`() {
        val p = ByteArray(0x80)
        p[0] = 5
        "YPC".toByteArray().copyInto(p, 0x01)
        "BK7231U-XRH-FBPRO".toByteArray().copyInto(p, 0x21)
        "HKV41B".toByteArray().copyInto(p, 0x41)
        "Soulear-318eb".toByteArray().copyInto(p, 0x51)
        // Status word 0xC801 as sent by the camera at full charge.
        p[0x77] = 0x01; p[0x78] = 0xC8.toByte()
        val info = assertNotNull(I4seasonProtocol.parseDevInfo(p))
        assertEquals("YPC", info.vendor)
        assertEquals("BK7231U-XRH-FBPRO", info.product)
        assertEquals("HKV41B", info.firmware)
        assertEquals("Soulear-318eb", info.ssid)
        assertEquals(100, info.batteryPercent)
    }

    @Test fun `short devinfo payload is rejected`() {
        assertNull(I4seasonProtocol.parseDevInfo(ByteArray(0x40)))
    }

    @Test fun `LED write sets the write bit on the camera LED id`() {
        assertContentEquals(byteArrayOf(0x11, 1, 100), I4seasonProtocol.ledWritePayload(on = true))
        assertContentEquals(byteArrayOf(0x11, 0, 0), I4seasonProtocol.ledWritePayload(on = false))
        assertContentEquals(byteArrayOf(0x01, 0, 0), I4seasonProtocol.ledReadPayload())
    }

    @Test fun `LED reply decodes status and brightness`() {
        assertEquals(I4seasonProtocol.LedState(on = true, brightness = 40), I4seasonProtocol.parseLed(byteArrayOf(0x11, 1, 40)))
        assertNull(I4seasonProtocol.parseLed(byteArrayOf(0x11, 1)))
    }

    @Test fun `FBPRO products get the vendor's 180 degree mount offset`() {
        assertEquals(180f, I4seasonProtocol.mountOffsetDegrees("BK7231U-XRH-FBPRO"))
        assertEquals(0f, I4seasonProtocol.mountOffsetDegrees("BK7231U-XRH"))
    }

    @Test fun `roll matches samples captured while turning the probe`() {
        // (packed sample, angle computed by the vendor's formula)
        val samples = listOf(
            0x00908e7f to 164.6f, // y=+35  z=-127
            0x00f99635 to 242.3f, // y=-101 z=-53
            0x00a9b415 to 280.9f, // y=-109 z=+21
            0x0051f048 to 59.9f,  // y=+124 z=+72
            0x2010d27a to 156.9f, // x negative, y=+52 z=-122
        )
        for ((g, expected) in samples) {
            val roll = assertNotNull(I4seasonProtocol.rollDegrees(g), "g=0x${g.toString(16)}")
            assertEquals(expected, roll, 0.1f, "g=0x${g.toString(16)}")
        }
    }

    @Test fun `roll with z of zero is 90 or 270 instead of a division by zero`() {
        assertEquals(90f, I4seasonProtocol.rollDegrees(0x00423c00)!!, 0.01f) // y=+143 z=0
    }

    @Test fun `roll is undefined when y and z are both zero`() {
        assertNull(I4seasonProtocol.rollDegrees(0x07f00000))
    }

    @Test fun `slope reflects the long axis`() {
        assertEquals(3.9f, I4seasonProtocol.slopeDegrees(0x00908e7f), 0.1f)
    }
}
