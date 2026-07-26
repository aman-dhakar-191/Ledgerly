package com.amandhakar.ledgerly.parser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Task 1.14: seed corpus for golden tests, sourced verbatim from docs/corpus-findings.md §8 (plus
 * the non-transaction near-misses in §4/§5/§11/§13) — written before any user confirmation exists,
 * so a change to the pre-filter or extractor can't silently break a format that already worked.
 *
 * Every user confirmation from the review inbox (Task 1.13) adds a further `GoldenTest` row to
 * Room; this file only covers the corpus itself.
 */
class GoldenCorpusTest {

    private val receivedAt = 1_700_000_000_000L

    @Test
    fun `ICICI account debit via UPI`() {
        val body = "ICICI Bank Acct XX924 debited for Rs 5000.00 on 09-Jun-26; " +
            "AMAN DHAKAR credited. UPI:987654321"
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(500_000L)
        assertThat(e.direction.value).isEqualTo(Direction.DEBIT)
        assertThat(e.accountLast4.value).isEqualTo("924")
        assertThat(e.reference.value).isEqualTo("987654321")
    }

    @Test
    fun `ICICI account credit via UPI`() {
        val body = "Dear Customer, Acct XX924 is credited with Rs 2000.00 on 10-Jun-26 " +
            "from JOHN DOE. UPI:123456789"
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(200_000L)
        assertThat(e.direction.value).isEqualTo(Direction.CREDIT)
    }

    @Test
    fun `ICICI account debit for a bill payment carries a reconcilable balance`() {
        val body = "ICICI Bank Acc XX924 debited Rs. 5000.00 on 12-Jun-26 InfoBIL*INFT*CC001." +
            "Avl Bal Rs. 45,231.50"
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(500_000L)
        assertThat(e.balanceAfter.value).isEqualTo(4_523_150L)
        assertThat(e.direction.value).isEqualTo(Direction.DEBIT)
    }

    @Test
    fun `ICICI ATM withdrawal uses Avb Bal, not Avl`() {
        val body = "ICICI Bank Acc XX924 debited Rs. 4,000.00 on 03-Jun-26 NFS*CASH WDL*. " +
            "Avb Bal Rs. 32,327.01."
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(400_000L)
        assertThat(e.balanceAfter.value).isEqualTo(3_232_701L)
    }

    @Test
    fun `ICICI card spend via UPI narration`() {
        val body = "ICICI Bank Credit Card XX6001 debited for INR 585.28 on 15-Jun-26 " +
            "for UPI-987654-ZOMATO"
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(58_528L)
        assertThat(e.accountLast4.value).isEqualTo("6001")
        assertThat(e.merchant.value).isEqualTo("ZOMATO")
    }

    @Test
    fun `ICICI card spend with available limit, not a balance`() {
        val body = "INR 1,630.00 spent using ICICI Bank Card XX6001 on 04-Jul-26 on BLINKIT. " +
            "Avl Limit: INR 15,468.00. If not you, call 1800 2662/SMS BLOCK 6001 to 9215676766."
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(163_000L)
        assertThat(e.balanceAfter.value).isNull()
        assertThat(e.merchant.value).isEqualTo("BLINKIT")
    }

    @Test
    fun `ICICI credit card bill payment is a transfer, never an expense`() {
        val body = "Dear Customer, Payment of INR 6941.21 has been received on your ICICI Bank " +
            "Credit Card Account 5001 on 20-Jul-26.Thank you."
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(694_121L)
        assertThat(e.direction.value).isEqualTo(Direction.CREDIT)
    }

    @Test
    fun `ICICI card statement is not a transaction`() {
        val body = "ICICI Bank Credit Card XX6001 Statement is sent to a***@gmail.com. " +
            "Total of Rs 10,391.94 or minimum of Rs 520.00 is due by 30-JUL-26."
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(1_039_194L)
    }

    @Test
    fun `ICICI card refund nets against a prior spend`() {
        val body = "AMAZON refund of Rs 367.09 credited to ICICI Bank Credit Card XX6001 on " +
            "13-JAN-26. Revised total due Rs 5,377.55, minimum due Rs .00"
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(36_709L)
        assertThat(e.direction.value).isEqualTo(Direction.CREDIT)
        // Task 2.8: the merchant leading a REFUND message, not "ICICI Bank Credit Card XX6001"
        // from "credited to" - matching against the original spend needs the real merchant.
        assertThat(e.merchant.value).isEqualTo("AMAZON")
    }

    @Test
    fun `ICICI EMI conversion is tagged but does not double count`() {
        val body = "Dear Customer, your transaction of Rs 16,110.00 using ICICI Bank Credit Card " +
            "XX5001 has been converted into EMI on 17-10-25."
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(1_611_000L)
    }

    @Test
    fun `SBI UPI debit has no currency marker at all`() {
        val body = "Dear UPI user A/C X3840 debited by 210.25 on date 23Jul26 trf to " +
            "AMAN DHAKAR Refno REF12345"
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(21_025L)
        assertThat(e.currency.value).isEqualTo("INR")
        assertThat(e.direction.value).isEqualTo(Direction.DEBIT)
    }

    @Test
    fun `SBI cash deposit credit with balance`() {
        val body = "Your A/C XXXXX999999 Credited INR 10000.00 on 05-Jul-26 -Deposited by Cash " +
            "by SELF. Avl Bal INR 55,000.00-SBI"
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(1_000_000L)
        assertThat(e.balanceAfter.value).isEqualTo(5_500_000L)
        assertThat(e.direction.value).isEqualTo(Direction.CREDIT)
    }

    @Test
    fun `SBI NEFT credit`() {
        val body = "Dear Customer, INR 5000.00 credited to your A/c No XX1234 on 10-Jul-26 " +
            "through NEFT with UTR ABC123XYZ by JOHN DOE, INFO: salary-SBI"
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(500_000L)
        assertThat(e.reference.value).isEqualTo("ABC123XYZ")
    }

    @Test
    fun `SBI NACH bounce is a real fee`() {
        val body = "Dear Customer, ECS/NACH dishonored in Acc XXXXX583840 due to insufficient " +
            "funds. Rs.295.00 debited to account as return charges.-SBI"
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(29_500L)
        assertThat(e.accountLast4.value).isEqualTo("3840")
    }

    @Test
    fun `SBI autopay scheduled is future intent, not a transaction`() {
        val body = "Dear UPI User, UPI AutoPay for NETFLIX COM debit of Rs.199.00 is scheduled " +
            "on .30/03/25, xyz@yapl. Please ensure sufficient balance in your account. -SBI"
        assertThat(classify(body)).isEqualTo(ParseClass.AUTOPAY_SCHEDULED)
    }

    @Test
    fun `Amazon Pay wallet debit has no balance or merchant`() {
        val body = "Your Apay Wallet balance is debited for INR 140.00. " +
            "Reference Number is 600789415458"
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(14_000L)
        assertThat(e.reference.value).isEqualTo("600789415458")
    }

    @Test
    fun `Amazon Pay wallet payment carries a reconcilable balance`() {
        val body = "Payment of Rs 114.00 using Apay Balance successful at merchant. " +
            "Updated Balance is Rs 267.98 - SMS by Juspay"
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(11_400L)
        assertThat(e.balanceAfter.value).isEqualTo(26_798L)
    }

    @Test
    fun `Zomato Money wallet payment uses lowercase balance label`() {
        val body = "Payment of Rs. 14.41 from Zomato Money Balance is successful. " +
            "Updated balance: Rs. 0.00. Contact zomatomoneysupport@zomato.com for queries. -ZOMATO"
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(1_441L)
        assertThat(e.balanceAfter.value).isEqualTo(0L)
    }

    @Test
    fun `axio purchase has no space after Rs and one decimal`() {
        val body = "Thank you for availing Pay Later credit of Rs656.7. For more info click " +
            "http://axio.example To report misuse call 18009877678 -axio"
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(65_670L)
    }

    @Test
    fun `axio monthly bill has a space and no decimals, and is a statement, not a transaction`() {
        // Task 2.6: BNPL_BILL_DUE sets the expected settlement amount/date, it never becomes a
        // transaction itself - the real debit is the separate ICICI-to-CAPITALFLOAT settlement.
        val body = "Your Pay Later bill of Rs 1698 will be debited on 5th of this month from " +
            "registered bank a/c. View Bill http://axio.example"
        assertThat(classify(body)).isEqualTo(ParseClass.STATEMENT)
        val amounts = StatementExtractor.extractAmounts(body)
        assertThat(amounts?.totalDue).isEqualTo(169_800L)
    }

    @Test
    fun `EPF balance SMS has trailing slash-dash amounts`() {
        val body = "Dear XXXXXXXX6775, your passbook balance against APKKP23388350000010194 is " +
            "Rs. 7,050/-. Contribution of Rs. 2,350/- for due month Oct-24 has been received."
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(705_000L)
    }

    @Test
    fun `Razorpay subscription payment is a real transaction`() {
        val body = "Your payment of Rs.999 for the subscription to TAGMANGO PRIVATE LIMITED is " +
            "successful. Any recurring subscription payments will be automatically charged " +
            "to your UPI from now - Razorpay"
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(99_900L)
    }

    @Test
    fun `Amazon collect request is a request, not a debit`() {
        val body = "SMARTWORKS TECH SOLUTIONS PVT has requested money from you on your AMAZON " +
            "app. On approving the request, Rs.140.00 will be debited from your a/c."
        assertThat(classify(body)).isEqualTo(ParseClass.COLLECT_REQUEST)
    }

    @Test
    fun `multi-currency USD standing instruction is a transaction, USD not INR`() {
        val body = "We have successfully processed payment of USD 23.60 to Merchant Anthropic, " +
            "as per Standing Instruction YO773YgqaO on 12/06/2026 for ICICI Bank Credit Card 6001."
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(2_360L)
        assertThat(e.currency.value).isEqualTo("USD")
    }

    @Test
    fun `debit card annual maintenance charge`() {
        val body = "Dear Customer, Your A/C ending with 3840 has been debited for INR 236.0 on " +
            "19-09-25 towards annual maintenance charges for your SBI Debit Card ending with 4517"
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
        val e = GenericExtractor.extract(body, receivedAt)
        assertThat(e.amount.value).isEqualTo(23_600L)
        assertThat(e.accountLast4.value).isEqualTo("3840")
    }
}
