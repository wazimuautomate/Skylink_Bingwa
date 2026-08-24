package com.example.data.persistence

import com.example.core.model.DailyRule
import com.example.core.model.NotificationItem
import com.example.core.model.OfferCategory
import com.example.core.model.OfferItem
import com.example.core.model.PaymentMethod
import com.example.core.model.PaymentStatus
import com.example.core.model.Promotion
import com.example.core.model.PromotionAccent
import com.example.core.model.PromotionKind
import com.example.core.model.PurchaseRecord
import com.example.core.model.UserProfile
import com.example.core.payment.PaymentTxnState
import com.example.data.payment.ActiveOrder
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the on-device persistence snapshot survives a JSON round-trip with the
 * same Moshi setup [LocalStore] uses. This is the part that must not silently break:
 * if serialisation of any persisted model (profile, purchases + enums, notifications,
 * active order) regresses, the customer's data would not survive a restart.
 */
class PersistedStateSerializationTest {

    private val adapter =
        Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(PersistedState::class.java)

    @Test
    fun `round-trips a fully populated snapshot unchanged`() {
        val original = PersistedState(
            profile = UserProfile(
                name = "Amina",
                primaryNumber = "0722000111",
                isOnboardingCompleted = true,
                notificationsEnabled = true
            ),
            theme = "DARK",
            favouriteIds = listOf("data_6", "sms_2"),
            boughtTodayIds = listOf("data_1"),
            purchases = listOf(
                PurchaseRecord(
                    id = "pur_1",
                    offerId = "data_6",
                    offerName = "2GB",
                    allowance = "2GB",
                    priceKsh = 110,
                    recipientNumber = "0722000111",
                    payerNumber = "0722000111",
                    mpesaCode = "RHK123",
                    timestampMillis = 1_000L,
                    status = PaymentStatus.RECEIVED,
                    paymentMethod = PaymentMethod.STK_PUSH,
                    clientRequestId = "crid-1",
                    orderReference = "ORD1"
                )
            ),
            notifications = listOf(
                NotificationItem(
                    id = "n1",
                    title = "Payment received",
                    body = "Recorded",
                    timestampMillis = 5L,
                    isRead = false,
                    deepLinkRoute = "activity"
                )
            ),
            recentRecipients = listOf("0722000111", "0700000000"),
            offers = listOf(
                OfferItem(
                    id = "data_6",
                    name = "2GB",
                    allowance = "2GB",
                    priceKsh = 110,
                    validity = "24 Hrs",
                    validityBand = "Daily",
                    category = OfferCategory.DATA,
                    dailyRule = DailyRule.BUY_AGAIN_TODAY,
                    description = "2GB of data valid 24 Hrs."
                )
            ),
            catalogueVersion = 7L,
            promotions = listOf(
                Promotion(
                    id = "promo_1",
                    kind = PromotionKind.OFFER,
                    tag = "HOT DEAL",
                    headline = "8GB + 400 Min",
                    subhead = "Monthly mega bundle",
                    ctaLabel = "Buy now",
                    accent = PromotionAccent.GREEN,
                    linkedOfferId = "data_13",
                    priorityWeight = 100,
                    startMillis = 1_000L,
                    endMillis = 2_000L
                )
            ),
            activeOrder = ActiveOrder(
                clientRequestId = "crid-2",
                offerId = "data_1",
                offerName = "1GB",
                priceKsh = 19,
                recipientNumber = "0700000000",
                payerNumber = "0722000111",
                isForSelf = false,
                state = PaymentTxnState.AWAITING_APPROVAL,
                orderReference = "ORD2"
            ),
            initialized = true
        )

        val restored = adapter.fromJson(adapter.toJson(original))

        assertNotNull(restored)
        assertEquals(original, restored)
    }

    @Test
    fun `round-trips an empty saved snapshot`() {
        val empty = PersistedState(initialized = true)
        val restored = adapter.fromJson(adapter.toJson(empty))
        assertEquals(empty, restored)
    }

    @Test
    fun `old snapshot without promotions field deserialises with default empty list`() {
        // A snapshot written before billboards sync existed (no "promotions" key). The
        // Kotlin default must apply so a pre-existing install still restores cleanly and
        // the app falls back to its seeded promotions. This backward-compat is critical.
        val oldJson = """
            {"favouriteIds":[],"boughtTodayIds":[],"purchases":[],"notifications":[],
             "recentRecipients":[],"offers":[],"catalogueVersion":3,"initialized":true}
        """.trimIndent()

        val restored = adapter.fromJson(oldJson)

        assertNotNull(restored)
        assertEquals(emptyList<Promotion>(), restored!!.promotions)
        assertEquals(3L, restored.catalogueVersion)
        assertTrue(restored.initialized)
    }
}
