package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.SenderRegistryDao

/**
 * Task 2.5/2.6: a WALLET or BNPL account carries no `last4`, so [SmsParsingPipeline.resolveAccount]'s
 * only path to it is `SenderRegistry.accountId` (docs/schema.md's "sender's own default account") -
 * this is what finally sets that link, for every raw sender ID already seen for [institution].
 * [SenderRegistryDao.getByInstitution] matches case-insensitively, so no normalisation needed here.
 */
suspend fun linkSendersToAccount(senderRegistryDao: SenderRegistryDao, institution: String, accountId: String, now: Long) {
    senderRegistryDao.getByInstitution(institution.trim()).forEach { sender ->
        senderRegistryDao.update(sender.copy(accountId = accountId, updatedAt = now))
    }
}
