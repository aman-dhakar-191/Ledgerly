package com.amandhakar.ledgerly.parser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AccountSuggestionTest {

    private val receivedAt = 1_700_000_000_000L

    @Test
    fun `messages for the same institution and last4 collapse into one suggestion`() {
        val messages = listOf(
            SourceMessage("ICICIT", "ICICI Bank Acct XX924 debited for Rs 500.00 on 09-Jun-26; X credited. UPI:1", receivedAt),
            SourceMessage("ICICIT", "ICICI Bank Acct XX924 debited for Rs 200.00 on 10-Jun-26; Y credited. UPI:2", receivedAt + 1_000),
        )

        val suggestions = suggestAccounts(messages)

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().institution).isEqualTo("ICICIT")
        assertThat(suggestions.single().last4).isEqualTo("924")
        assertThat(suggestions.single().messageCount).isEqualTo(2)
    }

    @Test
    fun `different last4 within the same institution produce separate suggestions`() {
        val messages = listOf(
            SourceMessage("ICICIT", "ICICI Bank Credit Card XX6001 debited for INR 585.28 on 15-Jun-26 for UPI-1-ZOMATO", receivedAt),
            SourceMessage(
                "ICICIT",
                "ICICI Bank Acc XX924 debited Rs. 4,000.00 on 03-Jun-26 NFS*CASH WDL*. Avb Bal Rs. 32,327.01.",
                receivedAt,
            ),
        )

        val suggestions = suggestAccounts(messages)

        assertThat(suggestions.map { it.last4 }).containsExactly("6001", "924")
    }

    @Test
    fun `lastSeenAt is the newest message's timestamp, not the last one in the list`() {
        val messages = listOf(
            SourceMessage("ICICIT", "ICICI Bank Acct XX924 debited for Rs 500.00 on 09-Jun-26; X credited. UPI:1", receivedAt + 5_000),
            SourceMessage("ICICIT", "ICICI Bank Acct XX924 debited for Rs 200.00 on 10-Jun-26; Y credited. UPI:2", receivedAt),
        )

        val suggestions = suggestAccounts(messages)

        assertThat(suggestions.single().lastSeenAt).isEqualTo(receivedAt + 5_000)
    }

    @Test
    fun `sampleMessage is the newest message's body, not the last one in the list`() {
        val newest = "ICICI Bank Acct XX924 debited for Rs 500.00 on 09-Jun-26; X credited. UPI:1"
        val messages = listOf(
            SourceMessage("ICICIT", newest, receivedAt + 5_000),
            SourceMessage("ICICIT", "ICICI Bank Acct XX924 debited for Rs 200.00 on 10-Jun-26; Y credited. UPI:2", receivedAt),
        )

        val suggestions = suggestAccounts(messages)

        assertThat(suggestions.single().sampleMessage).isEqualTo(newest)
    }

    @Test
    fun `a message with no extractable account number produces no suggestion`() {
        val messages = listOf(
            SourceMessage("JUSPAY", "Your Apay Wallet balance is debited for INR 140.00. Reference Number is 600789415458", receivedAt),
        )

        assertThat(suggestAccounts(messages)).isEmpty()
    }

    @Test
    fun `suggestions are ranked by message count, most frequent first`() {
        val frequent = List(3) {
            SourceMessage("ICICIT", "ICICI Bank Acct XX924 debited for Rs 500.00 on 09-Jun-26; X credited. UPI:$it", receivedAt)
        }
        val rare = listOf(
            SourceMessage("ICICIT", "ICICI Bank Acc XX111 debited Rs. 4,000.00 on 03-Jun-26 NFS*CASH WDL*. Avb Bal Rs. 1.00.", receivedAt),
        )

        val suggestions = suggestAccounts(frequent + rare)

        assertThat(suggestions.first().last4).isEqualTo("924")
        assertThat(suggestions.first().messageCount).isEqualTo(3)
    }
}
