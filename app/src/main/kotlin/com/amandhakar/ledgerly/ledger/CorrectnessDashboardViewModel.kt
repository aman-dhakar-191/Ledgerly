package com.amandhakar.ledgerly.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class CorrectnessDashboardViewModel @Inject constructor(
    private val calculator: CorrectnessDashboardCalculator,
) : ViewModel() {
    private val _report = MutableStateFlow<CorrectnessReport?>(null)
    val report: StateFlow<CorrectnessReport?> = _report.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _report.value = calculator.compute()
        }
    }
}
