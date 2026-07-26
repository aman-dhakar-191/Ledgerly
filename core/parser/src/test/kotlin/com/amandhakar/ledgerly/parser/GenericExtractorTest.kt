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
    fun `the axio BNPL spend format is recognised as a debit with no merchant`() {
        val body = "Thank you for availing Pay Later credit of Rs656.7. For more info click http://example.com " +
            "To report misuse call 18009877678 -axio"
        val result = GenericExtractor.extract(body, someReceivedAt)
        assertThat(result.amount.value).isEqualTo(65_670L)
        assertThat(result.direction.value).isEqualTo(Direction.DEBIT)
        assertThat(result.merchant.value).isNull()
    }

    @Test
    fun `the axio EMI-eligible BNPL spend variant also parses as a debit`() {
        val body = "Thanks for availing Rs4848.99 Pay Later credit. For more info on EMI, Rate of " +
            "Interest & Tenure click http://example.com -axio"
        val result = GenericExtractor.extract(body, someReceivedAt)
        assertThat(result.amount.value).isEqualTo(484_899L)
        assertThat(result.direction.value).isEqualTo(Direction.DEBIT)
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

    @Test
    fun `a refund message's merchant is the word leading it, not the card it lands on`() {
        // Task 2.8: without the dedicated anchor, "\bto\s+" would win instead and capture
        // "ICICI Bank Credit Card XX6001" from "credited to ICICI...".
        val body = "AMAZON refund of Rs 367.09 credited to ICICI Bank Credit Card XX6001 on " +
            "13-JAN-26. Revised total due Rs 5,377.55, minimum due Rs .00"
        val result = GenericExtractor.extract(body, someReceivedAt)
        assertThat(result.merchant.value).isEqualTo("AMAZON")
    }

    @Test
    fun `the real payee before 'credited' wins over the SMS BLOCK footer's phone number`() {
        // A real-world, very high-volume bug: "Call X for dispute. SMS BLOCK ### to ##########"
        // is a reversed/extra disclaimer phrasing the old footer detection never recognised, so the
        // generic "\bto\s+" anchor greedily captured the trailing phone number as the merchant.
        val body = "ICICI Bank Acct XX924 debited for Rs 500.00 on 23-Jul-26; JOHN DOE credited. " +
            "UPI:620427904038. Call 18002662 for dispute. SMS BLOCK 924 to 9215676766."
        val result = GenericExtractor.extract(body, someReceivedAt)
        assertThat(result.merchant.value).isEqualTo("JOHN DOE")
    }

    @Test
    fun `a Standing Instruction confirmation is a card debit even without a debit verb`() {
        val body = "We have successfully processed payment of INR 299.00 to Merchant Amazon, as per " +
            "Standing Instruction ABC123 on 12/06/2026 for ICICI Bank Credit Card 6001."
        val result = GenericExtractor.extract(body, someReceivedAt)
        assertThat(result.direction.value).isEqualTo(Direction.DEBIT)
        assertThat(result.merchant.value).isEqualTo("Amazon")
    }

    @Test
    fun `the 'for X, as per the Standing Instruction' phrasing also yields the merchant`() {
        val body = "Dear Customer, we have successfully processed the payment of INR 299.00 for Amazon, " +
            "as per the Standing Instruction ABC123, on 18/02/2026 for your ICICI Bank Credit Card 6001."
        val result = GenericExtractor.extract(body, someReceivedAt)
        assertThat(result.direction.value).isEqualTo(Direction.DEBIT)
        assertThat(result.merchant.value).isEqualTo("Amazon")
    }

    @Test
    fun `SBI's noun-form 'has a debit by' and 'has a credit by' are recognised as directions`() {
        val debit = GenericExtractor.extract(
            "Dear Customer, Your A/C XXXXX583840 has a debit by NACH of Rs 2,000.00 on 02/07/26. " +
                "Avl Bal Rs 3,203.53. Download YONO - SBI",
            someReceivedAt,
        )
        assertThat(debit.direction.value).isEqualTo(Direction.DEBIT)

        val credit = GenericExtractor.extract(
            "Dear Customer, Your A/C XXXXX583840 has a credit by Cheque of Rs 48,000.00 on 23/09/24. " +
                "Avl Bal Rs 48,245.29.-SBI",
            someReceivedAt,
        )
        assertThat(credit.direction.value).isEqualTo(Direction.CREDIT)
    }

    @Test
    fun `SBI's debit card POS confirmation is a debit even without 'debited' or 'spent'`() {
        val body = "Dear Customer, transaction number 419910834609 for Rs.549.00 by SBI Debit Card X3678 " +
            "done at 87062412 on 17Jul24 at 16:07:57. Your updated available balance is Rs.1004.85."
        val result = GenericExtractor.extract(body, someReceivedAt)
        assertThat(result.direction.value).isEqualTo(Direction.DEBIT)
    }

    @Test
    fun `SBI's NEFT credit payer name is extracted despite the trailing '-SBI' with no punctuation`() {
        // The generic "\bby\s+" anchor's lazy capture is allowed to consume "." and "," itself, so
        // with no punctuation anywhere before the trailing "-SBI", it can never find a stopping
        // point and the whole match silently fails - this needs its own ", INFO:"-anchored pattern.
        val body = "Dear Customer, INR 20,069.00 credited to your A/c No XX3840 on 29/10/2024 through " +
            "NEFT with UTR 38113634161DC by ACME EMPLOYER PVT LTD, INFO: Salary Oct 24-SBI"
        val result = GenericExtractor.extract(body, someReceivedAt)
        assertThat(result.merchant.value).isEqualTo("ACME EMPLOYER PVT LTD")
    }

    @Test
    fun `SBI's UPI-credit payer name is extracted from 'transfer from X Ref No' despite the trailing '-SBI'`() {
        val body = "Dear SBI User, your A/c X3840-credited by Rs.107 on 23Mar25 transfer from JOHN DOE " +
            "Ref No 101947667588 -SBI"
        val result = GenericExtractor.extract(body, someReceivedAt)
        assertThat(result.merchant.value).isEqualTo("JOHN DOE")
    }

    @Test
    fun `'Ref No' with a space extracts the actual reference number, not the literal word 'No'`() {
        val body = "Dear SBI User, your A/c X3840-credited by Rs.107 on 23Mar25 transfer from JOHN DOE " +
            "Ref No 101947667588 -SBI"
        val result = GenericExtractor.extract(body, someReceivedAt)
        assertThat(result.reference.value).isEqualTo("101947667588")
    }

    @Test
    fun `a card network merchant descriptor with an asterisk separator is extracted as the merchant`() {
        // docs/corpus-findings.md's ACCT_DEBIT_VIN and CARD_SPEND_LIMIT formats both carry a
        // merchant, but every anchor's character class omitted the literal "*" network-code
        // separator, so none of them could ever reach past it.
        val vin = GenericExtractor.extract(
            "Rs. 8,298.00 debited from ICICI Bank Acc XX924 on 02-Sep-25 VIN*MAKEMYTRI. " +
                "Bal Rs. 1,364.14. If not you call 18002662 or SMS BLOCK 924 to 9215676766",
            someReceivedAt,
        )
        assertThat(vin.merchant.value).isEqualTo("VIN*MAKEMYTRI")

        val cardSpend = GenericExtractor.extract(
            "USD 23.60 spent using ICICI Bank Card XX6001 on 12-Jul-26 on ANTHROPIC* CLAU. " +
                "Avl Limit: INR 8,356.02. If not you, call 1800 2662/SMS BLOCK 6001 to 9215676766.",
            someReceivedAt,
        )
        assertThat(cardSpend.merchant.value).isEqualTo("ANTHROPIC* CLAU")
    }

    @Test
    fun `InfoBIL bill-payment and NFS ATM codes are still never mistaken for a merchant`() {
        // Regression guard for the asterisk-merchant anchor above: both of these are themselves
        // "CODE*CODE*..." shaped and must not match it either.
        val infobil = GenericExtractor.extract(
            "ICICI Bank Acc XX924 debited Rs. 2,170.00 on 23-Jul-26 InfoBIL*INFT*FGR6.Avl Bal Rs. 8,611.98." +
                "To dispute call 18002662 or SMS BLOCK 924 to 9215676766",
            someReceivedAt,
        )
        assertThat(infobil.merchant.value).isNull()

        val atm = GenericExtractor.extract(
            "ICICI Bank Acc XX924 debited Rs. 4,000.00 on 03-Jun-26 NFS*CASH WDL*. Avb Bal Rs. 32,327.01.",
            someReceivedAt,
        )
        assertThat(atm.merchant.value).isNull()
    }
}
