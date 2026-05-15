package com.example.ubereatsoverlay

import java.util.Locale

object RangerEconomics {
    private const val GAS_PRICE = 4.10
    private const val MPG = 19.0
    private const val MAINTENANCE_PER_MILE = 0.26
    private const val RETURN_TO_ZONE_MULTIPLIER = 2.0

    val costPerMile: Double = (GAS_PRICE / MPG) + MAINTENANCE_PER_MILE

    data class Offer(val pay: Double, val miles: Double, val minutes: Double)

    fun netProfit(offer: Offer): Double = offer.pay - (offer.miles * costPerMile)

    fun hourlyRate(offer: Offer): Double {
        val net = netProfit(offer)
        val roundTripMinutes = offer.minutes * RETURN_TO_ZONE_MULTIPLIER
        return if (roundTripMinutes > 0) net / (roundTripMinutes / 60.0) else 0.0
    }

    fun formatSummary(offer: Offer): String {
        val net = netProfit(offer)
        val rate = hourlyRate(offer)
        return String.format(
            Locale.US,
            "Pay $%.2f - %.1f mi - %.0f min -> Net $%.2f - $%.2f/hr",
            offer.pay,
            offer.miles,
            offer.minutes,
            net,
            rate
        )
    }
}
