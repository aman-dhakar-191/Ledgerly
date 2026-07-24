package com.amandhakar.ledgerly.parser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TxnClassTest {

    @Test
    fun `a card-bill-payment SMS is tagged CARD_PAYMENT, not DEBIT`() {
        val body = "Dear Customer, Payment of INR 6941.21 has been received on your ICICI Bank " +
            "Credit Card Account 5001 on 20-Jul-26.Thank you."
        assertThat(classifyTransaction(body, Direction.CREDIT)).isEqualTo(TxnClass.CARD_PAYMENT)
    }

    @Test
    fun `card spend via UPI narration is CARD_SPEND`() {
        val body = "ICICI Bank Credit Card XX6001 debited for INR 585.28 on 15-Jun-26 for UPI-987654-ZOMATO"
        assertThat(classifyTransaction(body, Direction.DEBIT)).isEqualTo(TxnClass.CARD_SPEND)
    }

    @Test
    fun `card spend with available limit format is CARD_SPEND`() {
        val body = "INR 1,630.00 spent using ICICI Bank Card XX6001 on 04-Jul-26 on BLINKIT. " +
            "Avl Limit: INR 15,468.00."
        assertThat(classifyTransaction(body, Direction.DEBIT)).isEqualTo(TxnClass.CARD_SPEND)
    }

    @Test
    fun `a statement is not a plain debit or credit`() {
        val body = "ICICI Bank Credit Card XX6001 Statement is sent to a***@gmail.com. " +
            "Total of Rs 10,391.94 or minimum of Rs 520.00 is due by 30-JUL-26."
        assertThat(classifyTransaction(body, null)).isEqualTo(TxnClass.STATEMENT)
    }

    @Test
    fun `the alternate statement wording is also STATEMENT`() {
        val body = "Pay Total Amount Due of Rs 6,941.21 or Minimum Amount Due of Rs 2,170.00 " +
            "by 23-Jul-26 towards ICICI Bank Credit Card XX5001."
        assertThat(classifyTransaction(body, null)).isEqualTo(TxnClass.STATEMENT)
    }

    @Test
    fun `ATM withdrawal narration is ATM_WITHDRAWAL`() {
        val body = "ICICI Bank Acc XX924 debited Rs. 4,000.00 on 03-Jun-26 NFS*CASH WDL*. Avb Bal Rs. 32,327.01."
        assertThat(classifyTransaction(body, Direction.DEBIT)).isEqualTo(TxnClass.ATM_WITHDRAWAL)
    }

    @Test
    fun `a refund credited to a card is REVERSAL, not CARD_PAYMENT`() {
        val body = "AMAZON refund of Rs 367.09 credited to ICICI Bank Credit Card XX6001 on " +
            "13-JAN-26. Revised total due Rs 5,377.55, minimum due Rs .00"
        assertThat(classifyTransaction(body, Direction.CREDIT)).isEqualTo(TxnClass.REVERSAL)
    }

    @Test
    fun `a plain UPI debit with no other signal defaults to DEBIT`() {
        val body = "Dear UPI user A/C X3840 debited by 210.25 on date 23Jul26 trf to AMAN DHAKAR Refno REF999"
        assertThat(classifyTransaction(body, Direction.DEBIT)).isEqualTo(TxnClass.DEBIT)
    }

    @Test
    fun `a plain NEFT credit with no other signal defaults to CREDIT`() {
        val body = "Dear Customer, INR 5000.00 credited to your A/c No XX1234 on 10-Jul-26 " +
            "through NEFT with UTR ABC123XYZ by JOHN DOE, INFO: salary-SBI"
        assertThat(classifyTransaction(body, Direction.CREDIT)).isEqualTo(TxnClass.CREDIT)
    }
}
