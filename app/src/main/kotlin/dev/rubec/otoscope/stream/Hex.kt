package dev.rubec.otoscope.stream

/** Render a slice of a byte array as space-separated lowercase hex pairs.
 *  Used for the "first packet" debug log lines in the vendor sessions. */
internal fun ByteArray.toHex(off: Int, len: Int): String =
    (off until off + len).joinToString(" ") { "%02x".format(this[it]) }
