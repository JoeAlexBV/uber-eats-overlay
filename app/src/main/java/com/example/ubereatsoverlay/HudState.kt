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

    fun formatCompactBlock(): String {
        val lines = buildString {
            appendLine(formatLine("Pay", payText()))
            appendLine(formatLine("Net", netText()))
            append(formatLine("Rate", rateText()))
            formatTripLine()?.let { append("\n").append(it) }
        }
        return lines.trimEnd()
    }

    fun formatPhoneOverlay(): String = formatCompactBlock()

    fun payText(): String = formatMoney(pay)

    fun netText(): String = formatMoney(net)

    fun rateText(): String = formatRate(rate)

    fun tripText(): String = formatTripLine() ?: "No active trip"

    fun navigationTitle(): String = when (mode) {
        Mode.LIVE_OFFER,
        Mode.PAST_TRIP -> "Pay ${payText()}  Net ${netText()}"
        else -> headline
    }

    fun navigationText(): String = when (mode) {
        Mode.LIVE_OFFER,
        Mode.PAST_TRIP -> "${rateText()}  ${tripText()}"
        else -> "Waiting for the next offer"
    }

    fun mediaTitle(): String = when (mode) {
        Mode.LIVE_OFFER,
        Mode.PAST_TRIP -> "Pay ${payText()}  Net ${netText()}"
        else -> "Ranger Profit HUD"
    }

    fun mediaSubtitle(): String = when (mode) {
        Mode.LIVE_OFFER,
        Mode.PAST_TRIP -> "${rateText()}  ${tripText()}"
        else -> headline
    }

    fun mediaTitleLine(): String = when (mode) {
        Mode.LIVE_OFFER,
        Mode.PAST_TRIP -> "Pay ${payText()}"
        else -> "Ranger Profit HUD"
    }

    fun mediaArtistLine(): String = when (mode) {
        Mode.LIVE_OFFER,
        Mode.PAST_TRIP -> "Net ${netText()}"
        else -> headline
    }

    fun mediaAlbumLine(): String = when (mode) {
        Mode.LIVE_OFFER,
        Mode.PAST_TRIP -> "${rateText()}  ${tripText()}"
        else -> "Waiting for offers"
    }

    fun tileTitle(): String = when (mode) {
        Mode.LIVE_OFFER,
        Mode.PAST_TRIP -> String.format(
            Locale.US,
            "Pay %.2f Net %.2f Hr %.2f",
            pay,
            net,
            rate
        )
        else -> "Ranger Profit HUD"
    }

    fun tileText(): String = when (mode) {
        Mode.LIVE_OFFER,
        Mode.PAST_TRIP -> tripText()
        else -> headline
    }

    fun carNotificationTitle(): String = when (mode) {
        Mode.LIVE_OFFER,
        Mode.PAST_TRIP -> "Rate: ${rateText()}  ${tripText()}"
        else -> "Ranger HUD"
    }

    fun carNotificationText(): String = when (mode) {
        Mode.LIVE_OFFER,
        Mode.PAST_TRIP -> "Pay ${payText()}  Net ${netText()}"
        else -> headline
    }

    private fun formatLine(label: String, value: String): String = "$label: $value"

    private fun formatMoney(amount: Double): String =
        String.format(Locale.US, "$%.2f", amount)

    private fun formatRate(rate: Double): String =
        String.format(Locale.US, "$%.2f / hr", rate)

    fun formatTripLine(): String? {
        if (miles <= 0.0 && minutes <= 0.0) return null
        return String.format(Locale.US, "%.1f mi - %.0f min", miles, minutes)
    }

    fun statusTitle(): String = when (mode) {
        Mode.LIVE_OFFER -> "Live: $headline"
        Mode.PAST_TRIP -> "Past: $headline"
        else -> headline
    }

    fun publishUpdate(context: Context) {
        context.sendBroadcast(
            Intent(HudDashboard.ACTION_UPDATE).setPackage(context.packageName)
        )
    }
}
