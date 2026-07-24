package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.GoldenTestDao
import com.amandhakar.ledgerly.database.entity.GoldenTest
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** Task 1.14's DAO-touching half — [GoldenTestExportFormat.kt] does the actual JSON building/parsing. */
class GoldenTestExporter @Inject constructor(private val goldenTestDao: GoldenTestDao) {
    suspend fun exportJson(): String = buildGoldenTestsJson(goldenTestDao.observeAll().first())

    /** Returns the number of rows actually inserted; duplicates (by id) from a prior import are skipped. */
    suspend fun importJson(json: String): Int = parseGoldenTestsJson(json).count { imported ->
        val test = GoldenTest(
            id = imported.id,
            rawBody = imported.rawBody,
            expectedJson = imported.expectedJson,
            ruleId = null,
            createdAt = imported.createdAt,
            updatedAt = imported.updatedAt,
            deletedAt = null,
        )
        goldenTestDao.insertIgnoringConflicts(test) != -1L
    }
}
