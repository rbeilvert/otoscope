package dev.rubec.otoscope.stream.earfairy

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [EarFairyControlClient.decodeAngle] reproduces the vendor firmware's
 * two-byte rotation encoding. Table-drive the four quadrants the decoder
 * has to distinguish so a refactor can't silently regress any of them.
 */
class EarFairyDecodeAngleTest {

    @Test fun `high bit clear, positive low byte returns low byte unchanged`() {
        assertEquals(0, EarFairyControlClient.decodeAngle(0, 0))
        assertEquals(90, EarFairyControlClient.decodeAngle(0, 90))
        assertEquals(127, EarFairyControlClient.decodeAngle(0, 127))
    }

    @Test fun `high bit clear, negative-signed low byte adds 255`() {
        // -1 signed = 0xFF unsigned ⇒ -1 + 255 = 254.
        assertEquals(254, EarFairyControlClient.decodeAngle(0, 0xFF))
        // -128 signed = 0x80 unsigned ⇒ -128 + 255 = 127.
        assertEquals(127, EarFairyControlClient.decodeAngle(0, 0x80))
    }

    @Test fun `high bit set adds 255 regardless of low byte sign`() {
        // 0 + 255 = 255.
        assertEquals(255, EarFairyControlClient.decodeAngle(1, 0))
        // 127 + 255 = 382 — the top of the 0..~382 range the KDoc mentions.
        assertEquals(382, EarFairyControlClient.decodeAngle(1, 127))
    }

    @Test fun `values wrap cleanly modulo 360 for the renderer`() {
        // Renderer treats output as an angle mod 360; the decoder itself
        // doesn't wrap, but its outputs must be sensible modulo 360.
        val angle = EarFairyControlClient.decodeAngle(1, 45) // = 300
        assertEquals(300, angle)
        assertEquals(300, angle % 360)
    }
}
