package com.example.feature.home

import com.example.core.model.DailyRule
import com.example.core.model.OfferCategory
import com.example.core.model.OfferItem
import com.example.core.model.PaymentMethod
import com.example.core.model.PaymentStatus
import com.example.core.model.PurchaseRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The "buy this bundle once a day per number" rule, from the customer's side:
 * what the offer list says, and what checkout says when a number that already had
 * the bundle today is typed in again.
 *
 * The reset is the point of most of these tests — it must happen at Nairobi
 * midnight and nowhere else, so a purchase at 23:58 stops blocking at 00:00.
 */
class OncePerDayPerNumberTest {

    private val nairobi: TimeZone = TimeZone.getTimeZone("Africa/Nairobi")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance(nairobi)
        cal.set(year, month, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun onceOffer(id: String = "data_2") = OfferItem(
        id = id,
        name = "250MB",
        allowance = "250MB",
        priceKsh = 20,
        validity = "24 Hrs",
        category = OfferCategory.DATA,
        dailyRule = DailyRule.ONCE_PER_DAY
    )

    private fun repeatableOffer() = OfferItem(
        id = "sms_1",
        name = "10 SMS",
        allowance = "10 SMS",
        priceKsh = 5,
        validity = "24 Hrs",
        category = OfferCategory.SMS,
        dailyRule = DailyRule.BUY_AGAIN_TODAY
    )

    private fun purchase(
        offerId: String,
        recipient: String,
        whenMillis: Long,
        status: PaymentStatus = PaymentStatus.RECEIVED
    ) = PurchaseRecord(
        id = "pur_$whenMillis$recipient",
        offerId = offerId,
        offerName = "250MB",
        allowance = "250MB",
        priceKsh = 20,
        recipientNumber = recipient,
        payerNumber = recipient,
        mpesaCode = "ABC123",
        timestampMillis = whenMillis,
        status = status,
        paymentMethod = PaymentMethod.STK_PUSH
    )

    // --- the block at checkout ----------------------------------------------

    @Test
    fun `a number that already got the bundle today is blocked and told why`() {
        val now = at(2026, Calendar.AUGUST, 8, 14, 0)
        val history = listOf(purchase("data_2", "0712345678", at(2026, Calendar.AUGUST, 8, 9, 30)))

        val message = repeatPurchaseBlockMessage(onceOffer(), history, "0712345678", now)

        assertNotNull(message)
        assertTrue(message!!.contains("0712 345 678"))
        assertTrue(message.contains("once a day"))
        assertTrue(message.contains("after midnight"))
        assertTrue(message.contains("different number"))
    }

    @Test
    fun `a different number can still buy the same bundle today`() {
        val now = at(2026, Calendar.AUGUST, 8, 14, 0)
        val history = listOf(purchase("data_2", "0712345678", at(2026, Calendar.AUGUST, 8, 9, 30)))

        assertNull(repeatPurchaseBlockMessage(onceOffer(), history, "0798765432", now))
    }

    @Test
    fun `the same number in another format is still recognised`() {
        val now = at(2026, Calendar.AUGUST, 8, 14, 0)
        val history = listOf(purchase("data_2", "254712345678", at(2026, Calendar.AUGUST, 8, 9, 30)))

        assertNotNull(repeatPurchaseBlockMessage(onceOffer(), history, "0712345678", now))
    }

    @Test
    fun `a repeatable bundle is never blocked`() {
        val now = at(2026, Calendar.AUGUST, 8, 14, 0)
        val history = listOf(
            purchase("sms_1", "0712345678", at(2026, Calendar.AUGUST, 8, 9, 0)),
            purchase("sms_1", "0712345678", at(2026, Calendar.AUGUST, 8, 11, 0))
        )

        assertNull(repeatPurchaseBlockMessage(repeatableOffer(), history, "0712345678", now))
    }

    @Test
    fun `a purchase still being verified blocks with its own wording`() {
        val now = at(2026, Calendar.AUGUST, 8, 14, 0)
        val history = listOf(
            purchase(
                "data_2", "0712345678", at(2026, Calendar.AUGUST, 8, 13, 55),
                status = PaymentStatus.WAITING_VERIFY
            )
        )

        val message = repeatPurchaseBlockMessage(onceOffer(), history, "0712345678", now)
        assertNotNull(message)
        assertTrue(message!!.contains("still being verified"))
    }

    @Test
    fun `a failed purchase does not block the number`() {
        val now = at(2026, Calendar.AUGUST, 8, 14, 0)
        val history = listOf(
            purchase(
                "data_2", "0712345678", at(2026, Calendar.AUGUST, 8, 9, 0),
                status = PaymentStatus.FAILED
            )
        )

        assertNull(repeatPurchaseBlockMessage(onceOffer(), history, "0712345678", now))
    }

    @Test
    fun `an empty number is not blocked so the field can be typed into`() {
        val now = at(2026, Calendar.AUGUST, 8, 14, 0)
        val history = listOf(purchase("data_2", "0712345678", at(2026, Calendar.AUGUST, 8, 9, 0)))

        assertNull(repeatPurchaseBlockMessage(onceOffer(), history, "", now))
    }

    // --- the midnight reset --------------------------------------------------

    @Test
    fun `a purchase at 2358 no longer blocks at 0001 the next day`() {
        val boughtLastNight = at(2026, Calendar.AUGUST, 8, 23, 58)
        val history = listOf(purchase("data_2", "0712345678", boughtLastNight))

        val justBeforeMidnight = at(2026, Calendar.AUGUST, 8, 23, 59)
        assertNotNull(repeatPurchaseBlockMessage(onceOffer(), history, "0712345678", justBeforeMidnight))

        val justAfterMidnight = at(2026, Calendar.AUGUST, 9, 0, 1)
        assertNull(repeatPurchaseBlockMessage(onceOffer(), history, "0712345678", justAfterMidnight))
    }

    @Test
    fun `yesterday's purchase does not carry over`() {
        val now = at(2026, Calendar.AUGUST, 8, 10, 0)
        val history = listOf(purchase("data_2", "0712345678", at(2026, Calendar.AUGUST, 7, 22, 0)))

        assertNull(repeatPurchaseBlockMessage(onceOffer(), history, "0712345678", now))
        assertNull(boughtTodayNote(onceOffer(), history, now))
    }

    // --- the note on the offer list -----------------------------------------

    @Test
    fun `the list names the number that already got the bundle today`() {
        val now = at(2026, Calendar.AUGUST, 8, 14, 0)
        val history = listOf(purchase("data_2", "0712345678", at(2026, Calendar.AUGUST, 8, 9, 30)))

        assertEquals("Already bought today for 0712 345 678", boughtTodayNote(onceOffer(), history, now))
    }

    @Test
    fun `several numbers today are summarised without a wall of text`() {
        val now = at(2026, Calendar.AUGUST, 8, 14, 0)
        val history = listOf(
            purchase("data_2", "0722222222", at(2026, Calendar.AUGUST, 8, 12, 0)),
            purchase("data_2", "0712345678", at(2026, Calendar.AUGUST, 8, 9, 30))
        )

        // Newest first, so the number the customer just used leads.
        assertEquals("Already bought today for 0722 222 222 +1 more", boughtTodayNote(onceOffer(), history, now))
    }

    @Test
    fun `a repeatable bundle never carries the note`() {
        val now = at(2026, Calendar.AUGUST, 8, 14, 0)
        val history = listOf(purchase("sms_1", "0712345678", at(2026, Calendar.AUGUST, 8, 9, 0)))

        assertNull(boughtTodayNote(repeatableOffer(), history, now))
    }

    @Test
    fun `the same number bought twice is listed once`() {
        val now = at(2026, Calendar.AUGUST, 8, 14, 0)
        val history = listOf(
            purchase("data_2", "0712345678", at(2026, Calendar.AUGUST, 8, 9, 0)),
            purchase("data_2", "254712345678", at(2026, Calendar.AUGUST, 8, 11, 0))
        )

        assertEquals(1, boughtTodayRecipients(onceOffer(), history, now).size)
    }
}
