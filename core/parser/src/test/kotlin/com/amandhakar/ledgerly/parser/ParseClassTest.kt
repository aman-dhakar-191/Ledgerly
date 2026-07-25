package com.amandhakar.ledgerly.parser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ParseClassTest {

    @Test
    fun `OTP messages are never transactions even though every transaction field is present`() {
        val body = "798594 is One-Time Password for INR 585.28 transaction towards ZOMATO LIMI " +
            "using ICICI Bank Credit Card XX6001. OTPs are SECRET. DO NOT disclose"
        assertThat(classify(body)).isEqualTo(ParseClass.OTP)
    }

    @Test
    fun `declined transaction is not a transaction`() {
        val body = "Transaction of INR 354.22 at UPI-62022539084 on ICICI Bank Credit Card XX9001 " +
            "was declined due to insufficient credit limit. Available Credit Limit is INR 58.79"
        assertThat(classify(body)).isEqualTo(ParseClass.DECLINED)
    }

    @Test
    fun `standing instruction due in the future is not a transaction yet`() {
        val body = "Payment of INR 299.00 towards Merchant Amazon to be debited from ICICI Bank " +
            "Credit Card 6001, as per Standing Instruction YEyZRiKCDG, is due by 12/06/2026."
        assertThat(classify(body)).isEqualTo(ParseClass.SI_UPCOMING)
    }

    @Test
    fun `successfully processed standing instruction is a real transaction`() {
        val body = "We have successfully processed payment of USD 23.60 to Merchant Anthropic, as " +
            "per Standing Instruction YO773YgqaO on 12/06/2026 for ICICI Bank Credit Card 6001."
        assertThat(classify(body)).isEqualTo(ParseClass.TRANSACTION)
    }

    @Test
    fun `failed standing instruction is not a transaction`() {
        val body = "Dear Customer, payment of INR 1999.00 for Google Play for Standing Instructions " +
            "Y8dQwevrGU on your ICICI Bank Credit Card 5001 could not be processed."
        assertThat(classify(body)).isEqualTo(ParseClass.SI_FAILED)
    }

    @Test
    fun `scheduled autopay is not a transaction yet`() {
        val body = "Dear UPI User, UPI AutoPay for NETFLIX COM debit of Rs.199.00 is scheduled " +
            "on .30/03/25, xyz@yapl. Please ensure sufficient balance in your account. -SBI"
        assertThat(classify(body)).isEqualTo(ParseClass.AUTOPAY_SCHEDULED)
    }

    @Test
    fun `mandate upcoming notice is not a transaction yet`() {
        val body = "For the upcoming mandate set for 20-Aug-26, your account will be debited " +
            "with Rs.499.00 towards NETFLIX for the Upi Mandate."
        assertThat(classify(body)).isEqualTo(ParseClass.AUTOPAY_SCHEDULED)
    }

    @Test
    fun `a statement is never a transaction even though it also says 'is due by'`() {
        val body = "ICICI Bank Credit Card XX6001 Statement is sent to a***@gmail.com. " +
            "Total of Rs 10,391.94 or minimum of Rs 520.00 is due by 30-JUL-26."
        assertThat(classify(body)).isEqualTo(ParseClass.STATEMENT)
    }

    @Test
    fun `the 'pay total amount due' statement variant is also a statement`() {
        val body = "Pay Total Amount Due of Rs 6,941.21 or Minimum Amount Due of Rs 2,170.00 " +
            "by 23-Jul-26 towards ICICI Bank Credit Card XX5001."
        assertThat(classify(body)).isEqualTo(ParseClass.STATEMENT)
    }

    @Test
    fun `axio's BNPL bill due notice is a statement, not a transaction`() {
        val body = "Your Pay Later bill of Rs 1698 will be debited on 5th of this month from " +
            "registered bank a/c. View Bill http://example.com -axio"
        assertThat(classify(body)).isEqualTo(ParseClass.STATEMENT)
    }

    @Test
    fun `axio's BNPL credit limit change is not a transaction`() {
        val body = "Approved credit for your Pay Later account has been modified to Rs. 30000. " +
            "Please ensure timely payments on/before the due date for revaluation -axio"
        assertThat(classify(body)).isEqualTo(ParseClass.CREDIT_LIMIT_CHANGE)
    }

    @Test
    fun `a collect request is not a debit`() {
        val body = "SMARTWORKS TECH SOLUTIONS PVT has requested money from you on your AMAZON app. " +
            "On approving the request, Rs.140.00 will be debited from your a/c."
        assertThat(classify(body)).isEqualTo(ParseClass.COLLECT_REQUEST)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "ICICI Bank Acct XX924 debited for Rs 5000.00 on 09-Jun-26; AMAN DHAKAR credited. UPI:123|TRANSACTION",
            "Your A/C XXXXX999999 Credited INR 500.00 on 01-Jan-26 -Deposited by Cash. Avl Bal INR 500.00-SBI|TRANSACTION",
            "Your payment of Rs.999 for the subscription to TAGMANGO PRIVATE LIMITED is successful.|TRANSACTION",
        ],
    )
    fun `ordinary transaction messages are not caught by the pre-filter`(body: String, expected: String) {
        assertThat(classify(body)).isEqualTo(ParseClass.valueOf(expected))
    }
}
