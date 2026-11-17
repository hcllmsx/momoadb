package com.hcllmsx.momoadb.ui.diagnostics

import androidx.lifecycle.ViewModel
import com.hcllmsx.momoadb.diagnostics.DiagnosticFormatter
import com.hcllmsx.momoadb.diagnostics.DiagnosticLog
import com.hcllmsx.momoadb.diagnostics.DiagnosticLogger
import kotlinx.coroutines.flow.StateFlow

class DiagnosticLogViewModel : ViewModel() {
    val logs: StateFlow<List<DiagnosticLog>> = DiagnosticLogger.logs

    fun clear() {
        DiagnosticLogger.clear()
    }

    fun copyText(logs: List<DiagnosticLog>): String {
        return DiagnosticFormatter.format(logs)
    }
}
