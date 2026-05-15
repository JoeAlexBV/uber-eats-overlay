package com.example.ubereatsoverlay

import java.util.Locale

object RangerEconomics {
    private const val GAS_PRICE = 4.10
    private const val MPG = 22.0
    private const val MAINTENANCE_PER_MILE = 0.25

    val costPerMile: Double = (GAS_PRICE / MPG) + MAINTENANCE_PER_MILE

    data class Offer(val pay: Double, val miles: Double, val minutes: Double)

    fun netProfit(offer: Offer): Double = offer.pay - (offer.miles * costPerMile)

    fun hourlyRate(offer: Offer): Double {
        val net = netProfit(offer)
        return if (offer.minutes > 0) net / (offer.minutes / 60.0) else 0.0
    }

    fun formatSummary(offer: Offer): String {
        val net = netProfit(offer)
        val rate = hourlyRate(offer)
        return String.format(
            Locale.US,
            "Pay $%.2f · %.1f mi · %.0f min → Net $%.2f · $%.2f/hr",
            offer.pay,
            offer.miles,
            offer.minutes,
            net,
            rate
        )
    }
}
