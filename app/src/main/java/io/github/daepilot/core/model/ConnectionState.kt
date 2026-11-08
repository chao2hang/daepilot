package io.github.daepilot.core.model

import io.github.daepilot.core.network.ExitInfo

enum class ConnectionPhase { STOPPED, APPLYING, VERIFYING, CONNECTED, FAILED }

/**
 * Live outbound state. [ConnectionPhase.CONNECTED] only after a request actually
 * left through the proxy, so the exit IP describes the selected node rather than
 * the carrier. Shared with the control service so the ongoing notification and
 * the Xiaomi focus notification can show the same data as the UI.
 */
data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.STOPPED,
    val target: String = "",
    val exit: ExitInfo? = null,
    val latencyMs: Long? = null,
    val checkedAt: Long = 0L,
    val detail: String = "",
)
