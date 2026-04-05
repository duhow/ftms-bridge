package net.duhowpi.ftmsbridge

import android.graphics.Typeface
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.duhowpi.ftmsbridge.data.AppDatabase
import net.duhowpi.ftmsbridge.data.WorkoutSample
import net.duhowpi.ftmsbridge.data.WorkoutSession
import net.duhowpi.ftmsbridge.databinding.ActivityWorkoutDetailBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WorkoutDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWorkoutDetailBinding
    private lateinit var db: AppDatabase
    private var session: WorkoutSession? = null
    private val displayDateFormat = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkoutDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        db = AppDatabase.getInstance(this)

        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (sessionId < 0) { finish(); return }

        loadSession(sessionId)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_workout_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_export -> { session?.let { showExportDialog(it) }; true }
            R.id.action_delete -> { session?.let { confirmDelete(it) }; true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadSession(sessionId: Long) {
        lifecycleScope.launch {
            val (sess, samples) = withContext(Dispatchers.IO) {
                val s = db.sessionDao().getById(sessionId) ?: return@withContext null to emptyList()
                val sm = db.sampleDao().getAllBySession(sessionId)
                s to sm
            }
            if (sess == null) { finish(); return@launch }
            session = sess
            bindSession(sess, samples)
        }
    }

    private fun bindSession(session: WorkoutSession, samples: List<WorkoutSample>) {
        val typeName = when (session.machineType) {
            "TREADMILL"     -> "🏃 ${session.deviceName.ifEmpty { session.machineType }}"
            "INDOOR_BIKE"   -> "🚴 ${session.deviceName.ifEmpty { session.machineType }}"
            "CROSS_TRAINER" -> "🏋️ ${session.deviceName.ifEmpty { session.machineType }}"
            "STAIR_CLIMBER" -> "🪜 ${session.deviceName.ifEmpty { session.machineType }}"
            else -> session.deviceName.ifEmpty { session.machineType }
        }
        supportActionBar?.title = typeName
        binding.txtDetailDate.text = displayDateFormat.format(Date(session.startTimeMs))

        val durationSec = session.totalElapsedTimeSec.takeIf { it > 0 }
            ?: session.endTimeMs?.let { ((it - session.startTimeMs) / 1000).toInt() }
            ?: 0
        val mm = durationSec / 60
        val ss = durationSec % 60
        binding.txtDetailTime.text = "%d:%02d".format(mm, ss)
        binding.txtDetailDistance.text = if (session.totalDistanceM > 0)
            "%.2f".format(session.totalDistanceM / 1000.0) else "--"
        binding.txtDetailCalories.text = if (session.totalEnergyKcal > 0)
            "${session.totalEnergyKcal}" else "--"

        if (samples.isNotEmpty()) {
            setupChart(session, samples, durationSec)
        } else {
            binding.txtChartLabel.visibility = View.GONE
            binding.lineChart.visibility = View.GONE
        }

        // Device details
        if (session.deviceAddress.isNotEmpty()) {
            binding.txtDetailFtmsDevice.text = "${session.deviceName}  (${session.deviceAddress})"
        } else {
            binding.txtDetailFtmsDevice.text = session.deviceName.ifEmpty { getString(R.string.unknown_device) }
        }
        if (session.hrDeviceName.isNotEmpty() || session.hrDeviceAddress.isNotEmpty()) {
            binding.txtDetailHrDevice.text = "♥ ${session.hrDeviceName}  (${session.hrDeviceAddress})"
            binding.txtDetailHrDevice.visibility = View.VISIBLE
        }

        setupLapsTable(session, samples, durationSec)
    }

    private fun setupChart(session: WorkoutSession, samples: List<WorkoutSample>, durationSec: Int) {
        val isTreadmill = session.machineType == "TREADMILL"
        val isIndoorBike = session.machineType == "INDOOR_BIKE"

        if (!isTreadmill && !isIndoorBike) {
            binding.txtChartLabel.visibility = View.GONE
            binding.lineChart.visibility = View.GONE
            return
        }

        if (isTreadmill) {
            binding.txtChartLabel.text = "${getString(R.string.metric_speed)} / ${getString(R.string.metric_inclination)}"
            val speedPoints = samples.map { it.speedKmh.toFloat() }
            val inclinePoints = samples.map { it.inclinationPercent.toFloat() }
            binding.lineChart.setData(
                LineChartView.DataSeries(getString(R.string.metric_speed), Color.parseColor("#2196F3"), speedPoints),
                LineChartView.DataSeries(getString(R.string.metric_inclination), Color.parseColor("#FF9800"), inclinePoints),
                durationSec = durationSec
            )
        } else {
            binding.txtChartLabel.text = "${getString(R.string.metric_strides)} / ${getString(R.string.metric_resistance)}"
            val stridesPoints = samples.map { it.cadenceRpm.toFloat() }
            val resistancePoints = samples.map { it.resistanceLevel.toFloat() }
            binding.lineChart.setData(
                LineChartView.DataSeries(getString(R.string.metric_strides), Color.parseColor("#4CAF50"), stridesPoints),
                LineChartView.DataSeries(getString(R.string.metric_resistance), Color.parseColor("#9C27B0"), resistancePoints),
                durationSec = durationSec
            )
        }
    }

    private fun setupLapsTable(session: WorkoutSession, samples: List<WorkoutSample>, durationSec: Int) {
        if (samples.isEmpty()) return
        val isTreadmill = session.machineType == "TREADMILL"
        val isIndoorBike = session.machineType == "INDOOR_BIKE"
        if (!isTreadmill && !isIndoorBike) return

        data class LapData(val lapNum: Int, val startSec: Int, val endSec: Int, val avgValue1: Double, val avgValue2: Double)

        val laps = mutableListOf<LapData>()
        var lapNum = 1
        var lapStartSec = 0
        var lapStartIdx = 0
        val lapDistanceM = 1000

        samples.forEachIndexed { idx, sample ->
            if (sample.totalDistanceM >= lapNum * lapDistanceM) {
                val lapSamples = samples.subList(lapStartIdx, idx + 1)
                val avg1 = if (isTreadmill) lapSamples.map { it.speedKmh }.average()
                           else lapSamples.map { it.cadenceRpm }.average()
                val avg2 = if (isTreadmill) lapSamples.map { it.inclinationPercent }.average()
                           else lapSamples.map { it.resistanceLevel.toDouble() }.average()
                laps.add(LapData(lapNum, lapStartSec, sample.elapsedTimeSec, avg1, avg2))
                lapNum++
                lapStartSec = sample.elapsedTimeSec
                lapStartIdx = idx + 1
            }
        }

        if (laps.isEmpty()) return

        binding.txtLapsLabel.visibility = View.VISIBLE
        binding.layoutLapsTable.visibility = View.VISIBLE
        binding.layoutLapsTable.removeAllViews()

        // Header row
        val header = layoutInflater.inflate(R.layout.item_lap_row, binding.layoutLapsTable, false)
        val headerLabel1 = if (isTreadmill) getString(R.string.metric_speed) else getString(R.string.metric_strides)
        val headerLabel2 = if (isTreadmill) getString(R.string.metric_inclination) else getString(R.string.metric_resistance)
        header.findViewById<android.widget.TextView>(R.id.txtLapNum).apply { text = "#"; setTypeface(null, Typeface.BOLD) }
        header.findViewById<android.widget.TextView>(R.id.txtLapTime).apply { text = getString(R.string.metric_elapsed_time); setTypeface(null, Typeface.BOLD) }
        header.findViewById<android.widget.TextView>(R.id.txtLapVal1).apply { text = headerLabel1; setTypeface(null, Typeface.BOLD) }
        header.findViewById<android.widget.TextView>(R.id.txtLapVal2).apply { text = headerLabel2; setTypeface(null, Typeface.BOLD) }
        binding.layoutLapsTable.addView(header)

        // Data rows
        laps.forEach { lap ->
            val row = layoutInflater.inflate(R.layout.item_lap_row, binding.layoutLapsTable, false)
            val lapDurationSec = lap.endSec - lap.startSec
            val mm = lapDurationSec / 60
            val ss = lapDurationSec % 60
            row.findViewById<android.widget.TextView>(R.id.txtLapNum).text = "${lap.lapNum}"
            row.findViewById<android.widget.TextView>(R.id.txtLapTime).text = "%d:%02d".format(mm, ss)
            row.findViewById<android.widget.TextView>(R.id.txtLapVal1).text = String.format("%.1f", lap.avgValue1)
            row.findViewById<android.widget.TextView>(R.id.txtLapVal2).text = String.format("%.1f", lap.avgValue2)
            binding.layoutLapsTable.addView(row)
        }
    }

    private fun showExportDialog(session: WorkoutSession) {
        val options = arrayOf(
            getString(R.string.export_fit),
            getString(R.string.export_csv)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.export_format_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportSession(session, "fit")
                    1 -> exportSession(session, "csv")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportSession(session: WorkoutSession, format: String) {
        lifecycleScope.launch {
            val (file, mimeType) = withContext(Dispatchers.IO) {
                val samples = db.sampleDao().getAllBySession(session.id)
                val dir = getExternalFilesDir(null)?.resolve("exports") ?: filesDir.resolve("exports")
                val fileName = WorkoutExporter.exportFileName(session, format)
                when (format) {
                    "fit" -> {
                        val bytes = WorkoutExporter.toFit(session, samples)
                        val outFile = WorkoutExporter.writeBinaryToFile(dir, fileName, bytes)
                        Pair(outFile, "application/vnd.ant.fit")
                    }
                    else -> {
                        val content = WorkoutExporter.toCsv(session, samples)
                        val outFile = WorkoutExporter.writeToFile(dir, fileName, content)
                        Pair(outFile, "text/csv")
                    }
                }
            }
            val uri = FileProvider.getUriForFile(this@WorkoutDetailActivity, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.export_session)))
        }
    }

    private fun confirmDelete(session: WorkoutSession) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.session_delete_confirm))
            .setPositiveButton(getString(R.string.delete)) { _, _ -> deleteSession(session) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteSession(session: WorkoutSession) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.sessionDao().deleteById(session.id) }
            finish()
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }
}
