package dev.rubec.otoscope.stream

import android.net.Network
import dev.rubec.otoscope.debug.FileLog as Log
import java.net.DatagramSocket

/**
 * Bind [socket] to [network] so its outbound traffic goes through the camera's
 * Wi-Fi rather than whichever route Android's default resolver picks (typically
 * a VPN when one is active). Returns null on success (or when [network] is
 * null, since there's nothing to bind to), or a [TerminalErrors] code the
 * caller should stash and surface as a fatal condition.
 *
 * `Network.bindSocket()` is refused with EPERM on always-on VPNs in
 * lockdown mode, which is the only failure mode we've seen in the wild —
 * hence a fixed [TerminalErrors.NETWORK_BIND_FORBIDDEN] result rather than
 * exposing the raw exception.
 *
 * For callers with retry or fallback semantics, use `network?.bindSocket()`
 * directly. This helper is for one-shot binds where a failure means the
 * session can never reach the camera and should abort.
 */
internal fun bindOrTerminal(
    socket: DatagramSocket,
    network: Network?,
    tag: String,
): String? {
    val result = runCatching { network?.bindSocket(socket) }
    if (network != null && result.isFailure) {
        Log.w(tag, "bindSocket refused: ${result.exceptionOrNull()?.message}")
        return TerminalErrors.NETWORK_BIND_FORBIDDEN
    }
    return null
}
