package net.duhowpi.ftmsbridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.duhowpi.ftmsbridge.data.AppDatabase
import net.duhowpi.ftmsbridge.data.WorkoutSession
import net.duhowpi.ftmsbridge.databinding.ActivityWorkoutHistoryBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkoutHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWorkoutHistoryBinding
    private lateinit var db: AppDatabase
    private lateinit var adapter: SessionAdapter

    private val displayDateFormat = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault())

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importFromUri(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkoutHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = getString(R.string.workout_history)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        db = AppDatabase.getInstance(this)

        adapter = SessionAdapter(
            onDelete = { session -> confirmDelete(session) }
        )

        binding.rvSessions.apply {
            layoutManager = LinearLayoutManager(this@WorkoutHistoryActivity)
            adapter = this@WorkoutHistoryActivity.adapter
            addItemDecoration(
                DividerItemDecoration(this@WorkoutHistoryActivity, DividerItemDecoration.VERTICAL)
            )
        }

        loadSessions()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_workout_history, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_export_all -> { exportAllAsZip(); true }
            R.id.action_import -> {
                // .fit has no registered MIME type, so accept any file and validate content
                importLauncher.launch(arrayOf("*/*"))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun exportAllAsZip() {
        lifecycleScope.launch {
            val zipFile = withContext(Dispatchers.IO) {
                val sessions = db.sessionDao().getAll()
                if (sessions.isEmpty()) return@withContext null
                val dir = getExternalFilesDir(null)?.resolve("exports") ?: filesDir.resolve("exports")
                dir.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(dir, "ftmsbridge_backup_${stamp}.zip")
                ZipOutputStream(file.outputStream().buffered()).use { zip ->
                    for (session in sessions) {
                        val samples = db.sampleDao().getAllBySession(session.id)
                        val name = WorkoutExporter.exportFileName(session, "fit")
                            .removeSuffix(".fit") + "_${session.id}.fit"
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(WorkoutExporter.toFit(session, samples))
                        zip.closeEntry()
                    }
                }
                file
            }
            if (zipFile == null) {
                Toast.makeText(this@WorkoutHistoryActivity, R.string.no_sessions, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val uri = FileProvider.getUriForFile(this@WorkoutHistoryActivity, "${packageName}.fileprovider", zipFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.export_all)))
        }
    }

    private fun importFromUri(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext null
                val fitFiles = try {
                    if (FitDecoder.isZip(bytes)) FitDecoder.unzipFits(bytes) else listOf(bytes)
                } catch (e: Exception) {
                    return@withContext null
                }
                val existing = db.sessionDao().getAll().map { it.startTimeMs }.toHashSet()
                var imported = 0
                var skipped = 0
                for (fit in fitFiles) {
                    val decoded = runCatching { FitDecoder.decode(fit) }.getOrNull()
                    if (decoded == null || !existing.add(decoded.session.startTimeMs)) {
                        skipped++
                        continue
                    }
                    val newId = db.sessionDao().insert(decoded.session)
                    db.sampleDao().insertAll(decoded.samples.map { it.copy(sessionId = newId) })
                    imported++
                }
                Pair(imported, skipped)
            }
            val message = when {
                result == null || result.first + result.second == 0 -> getString(R.string.import_none)
                else -> getString(R.string.import_result, result.first, result.second)
            }
            Toast.makeText(this@WorkoutHistoryActivity, message, Toast.LENGTH_LONG).show()
            if (result != null && result.first > 0) loadSessions()
        }
    }

    private fun loadSessions() {
        lifecycleScope.launch {
            val sessions = withContext(Dispatchers.IO) { db.sessionDao().getAll() }
            if (sessions.isEmpty()) {
                binding.txtEmpty.visibility = View.VISIBLE
                binding.rvSessions.visibility = View.GONE
            } else {
                binding.txtEmpty.visibility = View.GONE
                binding.rvSessions.visibility = View.VISIBLE
                adapter.submitList(sessions)
            }
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
            val uri = FileProvider.getUriForFile(this@WorkoutHistoryActivity, "${packageName}.fileprovider", file)
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
            loadSessions()
        }
    }

    // ---- Adapter ------------------------------------------------------------

    inner class SessionAdapter(
        private val onDelete: (WorkoutSession) -> Unit
    ) : RecyclerView.Adapter<SessionAdapter.VH>() {

        private var items: List<WorkoutSession> = emptyList()

        fun submitList(list: List<WorkoutSession>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_session, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val txtType: TextView = itemView.findViewById(R.id.txtSessionType)
            private val txtDate: TextView = itemView.findViewById(R.id.txtSessionDate)
            private val txtDuration: TextView = itemView.findViewById(R.id.txtSessionDuration)
            private val txtDistance: TextView = itemView.findViewById(R.id.txtSessionDistance)
            private val txtHr: TextView = itemView.findViewById(R.id.txtSessionHr)

            fun bind(session: WorkoutSession) {
                val typeName = when (session.machineType) {
                    "TREADMILL"     -> "🏃 ${session.deviceName.ifEmpty { session.machineType }}"
                    "INDOOR_BIKE"   -> "🚴 ${session.deviceName.ifEmpty { session.machineType }}"
                    "CROSS_TRAINER" -> "🏋️ ${session.deviceName.ifEmpty { session.machineType }}"
                    "STAIR_CLIMBER" -> "🪜 ${session.deviceName.ifEmpty { session.machineType }}"
                    "HR_ONLY"       -> "❤️ ${session.deviceName.ifEmpty { "HR Sensor" }}"
                    else -> session.deviceName.ifEmpty { session.machineType }
                }
                txtType.text = typeName
                txtDate.text = displayDateFormat.format(Date(session.startTimeMs))

                val durationSec = session.totalElapsedTimeSec.takeIf { it > 0 }
                    ?: session.endTimeMs?.let { ((it - session.startTimeMs) / 1000).toInt() }
                    ?: 0
                val mm = durationSec / 60
                val ss = durationSec % 60
                txtDuration.text = getString(R.string.duration) + ": %d:%02d".format(mm, ss)

                txtDistance.text = if (session.totalDistanceM > 0)
                    "%.2f km".format(session.totalDistanceM / 1000.0) else ""

                txtHr.text = if (session.avgHeartRateBpm > 0)
                    "♥ ${session.avgHeartRateBpm} bpm" else ""

                itemView.setOnClickListener {
                    val intent = Intent(itemView.context, WorkoutDetailActivity::class.java)
                    intent.putExtra(WorkoutDetailActivity.EXTRA_SESSION_ID, session.id)
                    itemView.context.startActivity(intent)
                }
                itemView.setOnLongClickListener { onDelete(session); true }
            }
        }
    }
}
