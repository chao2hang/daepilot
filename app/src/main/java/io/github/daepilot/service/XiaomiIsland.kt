package io.github.daepilot.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.daepilot.R
import io.github.daepilot.core.model.ConnectionPhase
import io.github.daepilot.core.model.ConnectionState
import io.github.daepilot.core.model.DaemonStatus
import org.json.JSONObject

/**
 * Xiaomi HyperOS focus notification / Super Island support.
 *
 * The system exposes no API for this; an app attaches a `miui.focus.param` JSON
 * string to a normal notification, and HyperOS renders it as an island when the
 * app is on Xiaomi's focus-notification whitelist. We therefore query the
 * permission at runtime and fall back to the plain notification when it is
 * missing, so the notification never breaks on non-Xiaomi devices either.
 *
 * Layout: the collapsed pill keeps the shortest useful reading (flag + latency),
 * the expanded view adds the exit IP plus node and datacenter.
 */
object XiaomiIsland {
    private const val PROVIDER = "content://miui.statusbar.notification.public"
    private const val PIC_ICON = "miui.focus.pic_icon"
    private const val CACHE_MS = 60_000L
    private const val ICON_SIZE_PX = 96
    private val ACCENT = Color.parseColor("#0A59F7")

    /**
     * HyperOS rasterizes `miui.focus.pics` icons itself and turns a vector
     * drawable into a solid black square, so everything here is drawn into a
     * bitmap. The pill balances its two slots around the camera cutout, which
     * means a long text on one side leaves dead space on the other: the country
     * therefore rides in the icon slot as a flag and only the latency remains
     * as text.
     */
    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, Icon>()

    private fun islandIcon(context: Context, flagText: String?): Icon {
        val key = flagText.orEmpty()
        iconCache[key]?.let { return it }
        val icon = runCatching {
            val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (flagText != null) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = ICON_SIZE_PX * 0.78f
                    textAlign = Paint.Align.CENTER
                }
                val baseline = ICON_SIZE_PX / 2f - (paint.ascent() + paint.descent()) / 2f
                canvas.drawText(flagText, ICON_SIZE_PX / 2f, baseline, paint)
            } else {
                val radius = ICON_SIZE_PX * 0.24f
                canvas.drawRoundRect(
                    0f, 0f, ICON_SIZE_PX.toFloat(), ICON_SIZE_PX.toFloat(),
                    radius, radius,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT },
                )
                val inset = (ICON_SIZE_PX * 0.2f).toInt()
                ContextCompat.getDrawable(context, R.drawable.ic_stat_daepilot)?.let { glyph ->
                    glyph.setBounds(inset, inset, ICON_SIZE_PX - inset, ICON_SIZE_PX - inset)
                    glyph.draw(canvas)
                }
            }
            Icon.createWithBitmap(bitmap)
        }.getOrNull() ?: Icon.createWithResource(context, R.drawable.ic_stat_daepilot)
        iconCache[key] = icon
        return icon
    }

    data class Availability(
        val protocol: Int = 0,
        val supported: Boolean = false,
        val allowed: Boolean = false,
    ) {
        val usable: Boolean get() = supported && allowed
    }

    @Volatile
    private var cached: Availability? = null

    @Volatile
    private var cachedAt = 0L

    fun availability(context: Context, refresh: Boolean = false): Availability {
        val now = System.currentTimeMillis()
        cached?.let { if (!refresh && now - cachedAt < CACHE_MS) return it }
        val resolver = context.contentResolver
        val protocol = runCatching {
            Settings.System.getInt(resolver, "notification_focus_protocol", 0)
        }.getOrDefault(0)
        val allowed = runCatching {
            val extras = Bundle().apply { putString("package", context.packageName) }
            resolver.call(Uri.parse(PROVIDER), "canShowFocus", null, extras)
                ?.getBoolean("canShowFocus", false) ?: false
        }.getOrDefault(false)
        val supported = protocol >= 3 || runCatching {
            val extras = Bundle().apply { putString("package", context.packageName) }
            resolver.call(Uri.parse(PROVIDER), "isSupportIsland", null, extras)
                ?.getBoolean("isSupportIsland", false) ?: false
        }.getOrDefault(false)
        return Availability(protocol, supported, allowed).also {
            cached = it
            cachedAt = now
        }
    }

    /**
     * Extras for [android.app.Notification.extras], or null when unavailable.
     *
     * [expand] decides whether this post may auto-expand the island. Xiaomi
     * defaults `islandFirstFloat` to true (the first appearance expands) and
     * `enableFloat` to false (updates stay collapsed); we keep the first
     * appearance and ask for the expanded view only when the outbound really
     * changed, otherwise the 5-minute uptime refresh would keep popping the
     * island over the user's content.
     */
    fun focusExtras(
        context: Context,
        status: DaemonStatus,
        connection: ConnectionState,
        expand: Boolean = false,
    ): Bundle? {
        if (!status.running) return null
        if (!availability(context).usable) return null
        val params = paramJson(connection, expand) ?: return null
        val extras = Bundle()
        extras.putString("miui.focus.param", params)
        val pics = Bundle()
        val countryFlag =
            if (connection.phase == ConnectionPhase.CONNECTED) flag(connection.exit?.country) else null
        pics.putParcelable(PIC_ICON, islandIcon(context, countryFlag))
        extras.putBundle("miui.focus.pics", pics)
        return extras
    }

    private fun paramJson(connection: ConnectionState, expand: Boolean): String? {
        val phase = connection.phase
        if (phase == ConnectionPhase.STOPPED) return null
        val exit = connection.exit
        val ip = exit?.ip.orEmpty()
        val place = listOf(exit?.country.orEmpty(), exit?.colo.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        val latency = connection.latencyMs?.takeIf { it > 0 }?.let { "$it ms" }

        val frontTitle: String
        val title: String
        val content: String
        val statusText: String
        val pillText: String
        val highlight: Boolean
        when (phase) {
            ConnectionPhase.CONNECTED -> {
                frontTitle = "出口"
                title = ip.ifBlank { connection.target.ifBlank { "已连接" } }
                content = listOfNotNull(place, connection.target.takeIf { it.isNotBlank() }, latency)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                // The pill has no text slot, so only the number can ride there;
                // the flag plus latency is the short reading for the always-on
                // surfaces (AOD, status bar).
                statusText = listOfNotNull(
                    flag(exit?.country),
                    exit?.colo?.takeIf { flag(exit.country) == null && it.isNotBlank() },
                    latency,
                ).joinToString(" ").ifBlank { "已连接" }
                // The flag already rides in the icon slot; repeating it here
                // would widen the pill and reopen the dead space.
                pillText = latency ?: "已连接"
                highlight = true
            }
            ConnectionPhase.APPLYING, ConnectionPhase.VERIFYING -> {
                frontTitle = "DaePilot"
                title = if (phase == ConnectionPhase.APPLYING) "正在切换" else "正在验证"
                content = listOf(connection.target, connection.detail)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                statusText = if (phase == ConnectionPhase.APPLYING) "切换中" else "验证中"
                pillText = statusText
                highlight = false
            }
            ConnectionPhase.FAILED -> {
                frontTitle = "DaePilot"
                title = "连接异常"
                content = listOf(connection.target, connection.detail.ifBlank { "出口不可达" })
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                statusText = "异常"
                pillText = statusText
                highlight = false
            }
            ConnectionPhase.STOPPED -> return null
        }

        val detailText = if (phase == ConnectionPhase.CONNECTED) {
            "出口 $title" + (if (place.isNotBlank()) " · $place" else "")
        } else {
            "$title" + (if (content.isNotBlank()) " · $content" else "")
        }
        val paramV2 = JSONObject()
            .put("business", "vpn")
            .put("protocol", 1)
            .put("updatable", true)
            .put("islandFirstFloat", true)
            .put("enableFloat", expand)
            .put("timeout", 720)
            // The status-bar slot is narrow: a long IP leaves the pill half
            // empty, so only the short reading goes there.
            .put("ticker", statusText)
            .put("tickerPic", PIC_ICON)
            .put("aodTitle", statusText)
            .put("param_island", islandObject(frontTitle, title, pillText, highlight))
            .put(
                "baseInfo",
                JSONObject()
                    .put("title", "DaePilot · $frontTitle")
                    .put("content", detailText)
                    .put("colorTitle", "#006EFF")
                    .put("type", 2),
            )
            .put(
                "hintInfo",
                JSONObject()
                    .put("type", 1)
                    .put("title", if (phase == ConnectionPhase.CONNECTED) "$title · $place" else content),
            )
        return JSONObject().put("param_v2", paramV2).toString()
    }

    /** Regional-indicator flag for a two-letter country code, e.g. US. */
    private fun flag(country: String?): String? {
        val code = country?.trim()?.uppercase()?.takeIf {
            it.length == 2 && it.all { c -> c in 'A'..'Z' }
        } ?: return null
        return code.map { String(Character.toChars(0x1F1E6 - 'A'.code + it.code)) }
            .joinToString("")
    }

    private fun picInfo(): JSONObject = JSONObject().put("type", 1).put("pic", PIC_ICON)

    /**
     * The summary pill renders only an icon: the template library lists picInfo,
     * combinePicInfo and imageTextInfoRight type 6 for it, and type 6 reserves a
     * right-hand slot for a floating number that this OS build never fills, so
     * the pill ends up half empty. The short reading therefore travels in the
     * ticker (status bar text) and the full one in the expanded view.
     */
    private fun islandObject(
        frontTitle: String,
        title: String,
        pillText: String,
        highlight: Boolean,
    ): JSONObject {
        // HyperOS sizes the pill from the longest string in the island data and
        // takes the pill text from bigIslandArea.textInfo.title; a long content
        // here stretches both sides and leaves dead space next to the icon.
        // Keep the sibling textInfo to the short title only (system apps ship
        // it exactly like this) and carry the IP/detail in hintInfo/baseInfo,
        // which feed the expanded view and the notification body.
        val big = JSONObject()
            .put(
                "imageTextInfoLeft",
                JSONObject()
                    .put("type", 1)
                    .put("picInfo", picInfo()),
            )
            .put(
                "textInfo",
                JSONObject()
                    .put("title", pillText)
                    .put("showHighlightColor", highlight),
            )
        // No `pic` here on purpose: a custom picture makes HyperOS reserve the
        // wide image slot on the left of the cutout; omitting it falls back to
        // the compact notification icon, which is how system islands stay tight.
        val small = JSONObject().put("picInfo", JSONObject().put("type", 1))
        return JSONObject()
            .put("islandProperty", 1)
            .put("bigIslandArea", big)
            .put("smallIslandArea", small)
            .put("shareData", JSONObject().put("title", "$frontTitle $title"))
    }
}
