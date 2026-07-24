package com.amandhakar.ledgerly.ledger

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class DataExportViewModel @Inject constructor(private val dataExporter: DataExporter) : ViewModel() {
    suspend fun exportCsv(): String = dataExporter.exportCsv()
    suspend fun exportJson(): String = dataExporter.exportJson()
}
