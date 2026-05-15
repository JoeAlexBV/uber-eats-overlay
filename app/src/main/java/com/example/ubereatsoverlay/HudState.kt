package com.example.ubereatsoverlay

import android.content.Context
import android.content.Intent
import java.util.Locale

object HudState {

    enum class Mode {
        IDLE,
        LIVE_OFFER,
        PAST_TRIP,
        STANDBY
    }

    var mode: Mode = Mode.IDLE
        private set
    var pay: Double = 0.0
        private set
    var net: Double = 0.0
        private set
    var rate: Double = 0.0
        private set
    var miles: Double = 0.0
        private set
    var minutes: Double = 0.0
        private set
    var headline: String = "Monitoring"
        private set

    fun applyIdle() = apply(
        Mode.IDLE,
        RangerEconomics.Offer(0.0, 0.0, 0.0),
        "Monitoring"
    )

    fun applyStandby() = apply(
        Mode.STANDBY,
        RangerEconomics.Offer(0.0, 0.0, 0.0),
        "Standby"
    )

    fun applyLiveOffer(offer: RangerEconomics.Offer) = apply(
        Mode.LIVE_OFFER,
        offer,
        "Live offer"
    )

    fun applyPastTrip(offer: RangerEconomics.Offer) = apply(
        Mode.PAST_TRIP,
        offer,
        "Trip details"
    )

    fun applyTestOffer(offer: RangerEconomics.Offer) = apply(
        Mode.LIVE_OFFER,
        offer,
        "Test offer"
    )

    fun applyOnTrip(offer: RangerEconomics.Offer) = apply(
        Mode.IDLE,
        offer,
        "On trip"
    )

    internal fun apply(mode: Mode, offer: RangerEconomics.Offer, headline: String) {
        this.mode = mode
        pay = offer.pay
        miles = offer.miles
        minutes = offer.minutes
        net = RangerEconomics.netProfit(offer)
        rate = RangerEconomics.hourlyRate(offer)
        this.headline = headline
        syncLegacyFields()
    }

    private fun syncLegacyFields() {
        UberDataService.currentNet = net
        UberDataService.currentRate = rate
        UberDataService.latestStats = formatCompactBlock()
    }

    /** Single glanceable block — phone overlay, car HUD, and notifications. */
    fun formatCompactBlock(): String {
        val lines = buildString {
            appendLine(formatLine("Pay", formatMoney(pay)))
            appendLine(formatLine("Net", formatMoney(net)))
            append(formatLine("Rate", formatRate(rate)))
            formatTripLine()?.let { append("\n").append(it) }
        }
        return lines.trimEnd()
    }

    fun formatPhoneOverlay(): String = formatCompactBlock()

    private fun formatLine(label: String, value: String): String = "$label: $value"

    private fun formatMoney(amount: Double): String =
        String.format(Locale.US, "$%.2f", amount)

    private fun formatRate(rate: Double): String =
        String.format(Locale.US, "$%.2f / hr", rate)

    fun formatTripLine(): String? {
        if (miles <= 0.0 && minutes <= 0.0) return null
        return String.format(Locale.US, "%.1f mi · %.0f min", miles, minutes)
    }

    fun statusTitle(): String = when (mode) {
        Mode.LIVE_OFFER -> "● $headline"
        Mode.PAST_TRIP -> "◌ $headline"
        else -> headline
    }

    fun publishUpdate(context: Context) {
        context.sendBroadcast(
            Intent(HudDashboard.ACTION_UPDATE).setPackage(context.packageName)
        )
    }
}
