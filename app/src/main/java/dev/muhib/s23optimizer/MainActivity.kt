package dev.muhib.s23optimizer

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.LaunchedEffect
import dev.muhib.s23optimizer.ui.*

class MainActivity : ComponentActivity() {
    private lateinit var model: OptimizerViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[OptimizerViewModel::class.java]
        model.graph.startTimer.attach(this, restored = savedInstanceState != null)
        // Debug builds only: CI opens each screen directly to capture landing-page screenshots.
        val initialScreen = if (BuildConfig.DEBUG) intent.getStringExtra("screen") else null
        if (BuildConfig.DEBUG && savedInstanceState == null && intent.getBooleanExtra("autoAudit", false)) model.audit("BEFORE")
        setContent {
            val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> model.export(uri) }
            val csvExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> model.exportCsv(uri) }
            LaunchedEffect(model) {
                model.share.collect { uri ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("Measured report", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { startActivity(Intent.createChooser(send, "Share measured report")) }
                        .onFailure { model.message.value = "No sharing app is available: ${it.message}" }
                }
            }
            OptimizerTheme {
                OptimizerApp(model, export = { if (model.prepareExport()) exporter.launch("S23-Optimizer-report.pdf") },
                    exportCsv = { csvExporter.launch("S23-Optimizer-measured.csv") }, initialScreen = initialScreen)
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) {
            model.graph.access.refresh()
            model.refreshStarts()
        }
    }
}
