package com.example.core.personalization

import com.example.core.model.DailyRule
import com.example.core.model.OfferCategory
import com.example.core.model.OfferItem
import com.example.core.model.PaymentMethod
import com.example.core.model.PaymentStatus
import com.example.core.model.PurchaseRecord

/**
 * Shared fixtures for the personalization tests.
 *
 * All timestamps are anchored to a fixed Nairobi instant so every day-boundary,
 * hour-band and weekday assertion is deterministic. Africa/Nairobi is UTC+03:00
 * with no daylight saving, which is exactly what the engine assumes.
 */
object PersonalizationTestData {

    const val HOUR = 3_600_000L
    const val DAY = 86_400_000L

    /** 2024-01-10 12:00:00 Africa/Nairobi — a Wednesday. */
    const val NOW = 1_704_877_200_000L

    /** 2024-01-10 00:00:00 Africa/Nairobi. */
    const val MIDNIGHT = NOW - 12L * HOUR

    /** A Nairobi wall-clock instant relative to the anchor day. */
    fun at(dayOffset: Int, hour: Int, minute: Int = 0): Long =
        MIDNIGHT + dayOffset * DAY + hour * HOUR + minute * 60_000L

    fun purchase(
        offerId: String,
        ts: Long,
        price: Int = 50,
        status: PaymentStatus = PaymentStatus.RECEIVED,
        recipient: String = "0700000001",
        payer: String = "0700000001",
        offerName: String = "Bundle $offerId",
        allowance: String = "1 GB"
    ): PurchaseRecord = PurchaseRecord(
        id = "rec_${offerId}_${ts}_${status.name}_$price",
        offerId = offerId,
        offerName = offerName,
        allowance = allowance,
        priceKsh = price,
        recipientNumber = recipient,
        payerNumber = payer,
        mpesaCode = "TESTCODE",
        timestampMillis = ts,
        status = status,
        paymentMethod = PaymentMethod.STK_PUSH
    )

    fun offer(
        id: String,
        price: Int = 50,
        category: OfferCategory = OfferCategory.DATA,
        band: String = PersonalizationEngine.BAND_DAILY,
        favourite: Boolean = false
    ): OfferItem = OfferItem(
        id = id,
        name = "Offer $id",
        allowance = "1 GB",
        priceKsh = price,
        validity = "24 hours",
        validityBand = band,
        category = category,
        dailyRule = DailyRule.BUY_AGAIN_TODAY,
        isFavourite = favourite
    )
}
