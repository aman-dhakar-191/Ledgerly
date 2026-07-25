package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.SenderRegistryDao

/**
 * Task 2.5: a WALLET account carries no `last4`, so [SmsParsingPipeline.resolveAccount]'s only
 * path to it is `SenderRegistry.accountId` (docs/schema.md's "sender's own default account") -
 * this is what finally sets that link, for every raw sender ID already seen for [institution].
 */
suspend fun linkSendersToAccount(senderRegistryDao: SenderRegistryDao, institution: String, accountId: String, now: Long) {
    senderRegistryDao.getByInstitution(institution.trim().uppercase()).forEach { sender ->
        senderRegistryDao.update(sender.copy(accountId = accountId, updatedAt = now))
    }
}
