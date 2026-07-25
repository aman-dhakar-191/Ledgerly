package com.amandhakar.ledgerly.parser

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Test

class GenericExtractorTest {

    private val someReceivedAt = 1_700_000_000_000L

    @Test
    fun `amount with commas and decimals, Rs prefix, and Avl Bal are all extracted`() {
        val body = "ICICI Bank Acc XX924 debited Rs. 4,000.00 on 03-Jun-26 NFS*CASH WDL*. " +
            "Avb Bal Rs. 32,327.01."
        val result = GenericExtractor.extract(body, someReceivedAt)
        assertThat(result.amount.value).isEqualTo(400_000L)
        assertThat(result.balanceAfter.value).isEqualTo(3_232_701L)
        assertThat(result.direction.value).isEqualTo(Direction.DEBIT)
        assertThat(result.currency.value).isEqualTo("INR")
    }

    @Test
    fun `INR prefix and credit direction`() {
        val body = "Dear Customer, INR 5000.00 credited to your A/c No XX1234 on 10-Jul-26 " +
            "through NEFT with UTR ABC123XYZ by JOHN DOE, INFO: salary-SBI"
        val result = GenericExtractor.extract(body, someReceivedAt)
        assertThat(result.amount.value).isEqualTo(500_000L)
        assertThat(result.direction.value).isEqualTo(Direction.CREDIT)
        assertThat(result.reference.value).isEqualTo("ABC123XYZ")
        assertThat(result.accountLast4.value).isEqualTo("1234")
    }

    @Test
    fun `bare amount with no currency marker, SBI-style debited by`() {
        val body = "Dear UPI user A/C X3840 debited by 210.25 on date 23Jul26 trf to AMAN DHAKAR Refno REF999"
        val result = GenericExtractor.extract(body, someReceivedAt)
        assertThat(result.amount.value).isEqualTo(21_025L)
        assertThat(result.direction.value).isEqualTo(Direction.DEBIT)
        assertThat(result.accountLast4.value).isEqualTo("3840")
    }

    @Test
    fun `Avl Limit is never mistaken for the transaction amount or a balance`() {
        val body = "INR 1,630.00 spent using ICICI Bank Card XX6001 on 04-Jul-26 on BLINKIT. " +
            "Avl Limit: INR 15,468.00. If not you, call 1800 2662"
        val result = GenericExtractor.extract(body, someReceivedAt)
        assertThat(result.amount.value).isEqualTo(163_000L)
        assertThat(result.balanceAfter.value).isNull()
        assertThat(result.availableLimit.value).isEqualTo(1_546_800L)
    }

    @Test
    fun `the dispute-call disclaimer footer is never mistaken for a merchant`() {
        val body = "ICICI Bank Acc XX924 debited Rs. 2,170.00 on 23-Jul-26 InfoBIL*INFT*FGR6.Avl Bal Rs. 8,611.98." +
            "To dispute call 18002662 or SMS BLOCK 924 to 9215676766"
        val result = GenericExtractor.extract(body, someReceivedAt)
        assertThat(result.amount.value).isEqualTo(217_000L)
        assertThat(result.balanceAfter.value).isEqualTo(861_198L)
        assertThat(result.merchant.value).isNull()
    }

    @Test
    fun `a wallet payment with no debit verb is still recognised as a debit`() {
        val body = "Payment of Rs 114.00 using Apay Balance successful at merchant. " +
            "Updated Balance is Rs 267.98 - SMS by Juspay"
        val result = GenericExtractor.extract(body, someReceivedAt)
        assertThat(result.amount.value).isEqualTo(11_400L)
        assertThat(result.direction.value).isEqualTo(Direction.DEBIT)
        assertThat(result.balanceAfter.value).isEqualTo(26_798L)
    }

    @Test
    fun `the Zomato wallet payment format also parses as a debit with a balance`() {
        val body = "Payment of Rs. 14.41 from Zomato Money Balance is successful. " +
            "Updated balance: Rs. 0.00. Contact zomatomoneysupport@zomato.com for queries. -ZOMATO"
        val result = GenericExtractor.extract(body, someReceivedAt)
        assertThat(result.amount.value).isEqualTo(1_441L)
        assertThat(result.direction.value).isEqualTo(Direction.DEBIT)
        assertThat(result.balanceAfter.value).isEqualTo(0L)
    }

    @Test
    fun `an explicit credit verb still wins even when the body also says 'payment of X successful'`() {
        val body = "Payment of Rs 500.00 refund is successful; amount credited to your account."
        val result = GenericExtractor.extract(body, someReceivedAt)
        assertThat(result.direction.value).isEqualTo(Direction.CREDIT)
    }

    @Test
    fun `missing date falls back to receivedAt`() {
        val body = "Payment of Rs 114.00 using Apay Balance successful at merchant. " +
            "Updated Balance is Rs 267.98 - SMS by Juspay"
        val result = GenericExtractor.extract(body, someReceivedAt)
        assertThat(result.occurredAt.value).isEqualTo(someReceivedAt)
        assertThat(result.occurredAt.confidence).isEqualTo(0.5f)
    }

    @Test
    fun `dd-MMM-yy date parses to the correct epoch day`() {
        val body = "ICICI Bank Acct XX924 debited for Rs 5000.00 on 09-Jun-26; AMAN DHAKAR credited. UPI:123"
        val result = GenericExtractor.extract(body, someReceivedAt)
        val expected = LocalDate.of(2026, 6, 9).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertThat(result.occurredAt.value).isEqualTo(expected)
    }

    @Test
    fun `garbage input yields all-null fields with zero confidence and does not throw`() {
        val result = GenericExtractor.extract("asdf jkl; not a transaction at all 12345", someReceivedAt)
        assertThat(result.amount.value).isNull()
        assertThat(result.amount.confidence).isEqualTo(0f)
        assertThat(result.balanceAfter.value).isNull()
        assertThat(result.reference.value).isNull()
    }

    @Test
    fun `empty body does not throw`() {
        val result = GenericExtractor.extract("", someReceivedAt)
        assertThat(result.amount.value).isNull()
    }
}
