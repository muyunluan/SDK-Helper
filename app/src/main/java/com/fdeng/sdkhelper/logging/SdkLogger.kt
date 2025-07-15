package com.fdeng.sdkhelper.logging

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SdkLogger {

	enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR, NONE }

	interface LogListener {
		fun onLog(level: Level, tag: String, message: String)
	}

	private const val DEFAULT_TAG = "YourSdk"
	private var logTag: String = DEFAULT_TAG

	private var isInternalBuild = false
	private var currentLevel = Level.WARN

	private var listener: LogListener? = null

	private var isFileLoggingEnabled = false
	private var logDir: File? = null
	private var logFile: File? = null
	private var bufferedWriter: BufferedWriter? = null

	private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
	private val filenameDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

	private var currentLogDate: String = ""

	private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	// --- Public Config API ---

	fun setInternalBuild(isInternal: Boolean) {
		isInternalBuild = isInternal
	}

	fun setLogLevel(level: Level) {
		currentLevel = level
	}

	fun setLogTag(tag: String) {
		if (tag.isNotBlank()) logTag = tag
	}

	fun setLogListener(logListener: LogListener?) {
		listener = logListener
	}

	fun enableFileLogging(context: Context, enable: Boolean) {
		isFileLoggingEnabled = enable

		if (enable) {
			logDir = File(context.filesDir, "logs").apply { mkdirs() }
			rotateLogFileIfNeeded()
		} else {
			closeLogWriter()
		}
	}

	// --- Logging Methods ---

	fun v(message: String) = log(Level.VERBOSE, message)
	fun d(message: String) = log(Level.DEBUG, message)
	fun i(message: String) = log(Level.INFO, message)
	fun w(message: String) = log(Level.WARN, message)
	fun e(message: String) = log(Level.ERROR, message)

	// --- Core Logging Logic ---

	private fun log(level: Level, message: String) {
		if (!shouldLog(level)) return

		val formatted = formatMessage(level, message)

		when (level) {
			Level.VERBOSE -> Log.v(logTag, formatted)
			Level.DEBUG -> Log.d(logTag, formatted)
			Level.INFO -> Log.i(logTag, formatted)
			Level.WARN -> Log.w(logTag, formatted)
			Level.ERROR -> Log.e(logTag, formatted)
			else -> {}
		}

		if (isFileLoggingEnabled) {
			writeToFileBuffered(formatted)
		}

		listener?.onLog(level, logTag, message)
	}

	private fun shouldLog(level: Level): Boolean {
		if (level == Level.VERBOSE && !isInternalBuild) return false
		return level.ordinal >= currentLevel.ordinal && currentLevel != Level.NONE
	}

	private fun formatMessage(level: Level, message: String): String {
		val timestamp = timestampFormat.format(Date())
		return "$timestamp [${level.name}] $message"
	}

	// --- Daily File Rotation ---

	private fun rotateLogFileIfNeeded() {
		logScope.launch {
			val today = filenameDateFormat.format(Date())
			if (today != currentLogDate || bufferedWriter == null) {
				closeLogWriter()
				currentLogDate = today
				logFile = File(logDir, "sdk_log_$today.txt")
				try {
					bufferedWriter = BufferedWriter(FileWriter(logFile, true)).apply {
						write("---- LOG STARTED ${timestampFormat.format(Date())} ----\n")
						flush()
					}
				} catch (e: IOException) {
					Log.e(logTag, "Failed to create new daily log file", e)
				}
			}
		}
	}

	private fun writeToFileBuffered(message: String) {
		logScope.launch {
			try {
				rotateLogFileIfNeeded()
				bufferedWriter?.apply {
					write(message)
					write("\n")
				}
			} catch (e: IOException) {
				Log.e(logTag, "BufferedWriter write failed", e)
				restartWriter()
			}
		}
	}

	private fun restartWriter() {
		closeLogWriter()
		rotateLogFileIfNeeded()
	}

	private fun closeLogWriter() {
		try {
			bufferedWriter?.apply {
				write("---- LOG CLOSED ${timestampFormat.format(Date())} ----\n")
				flush()
				close()
			}
		} catch (e: IOException) {
			Log.e(logTag, "Failed to close log writer", e)
		} finally {
			bufferedWriter = null
		}
	}

	fun flush() {
		logScope.launch {
			try {
				bufferedWriter?.flush()
			} catch (e: IOException) {
				Log.e(logTag, "Failed to flush log", e)
			}
		}
	}

	fun clearLogFile() {
		logScope.launch {
			closeLogWriter()
			logFile?.delete()
			rotateLogFileIfNeeded()
		}
	}

	fun shutdown() {
		closeLogWriter()
		logScope.cancel()
	}

	fun getLogFile(): File? = logFile

	fun exportToExternal(context: Context): List<File> {
		val exportedFiles = mutableListOf<File>()

		if (logDir == null) {
			Log.w(logTag, "Log directory not initialized")
			return emptyList()
		}

		val internalFiles = logDir?.listFiles { _, name ->
			name.startsWith("sdk_log_") && name.endsWith(".txt")
		} ?: return emptyList()

		val externalDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "YourSdkLogs")
		if (!externalDir.exists()) externalDir.mkdirs()

		internalFiles.forEach { file ->
			try {
				val outFile = File(externalDir, file.name)
				file.copyTo(outFile, overwrite = true)
				exportedFiles.add(outFile)
			} catch (e: IOException) {
				Log.e(logTag, "Failed to export log: ${file.name}", e)
			}
		}

		return exportedFiles
	}

	fun exportToExternalZipped(context: Context): File? {
		val logFiles = logDir?.listFiles { _, name ->
			name.startsWith("sdk_log_") && name.endsWith(".txt")
		} ?: return null

		if (logFiles.isEmpty()) return null

		val externalDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "YourSdkLogs")
		if (!externalDir.exists()) externalDir.mkdirs()

		val zipFile = File(externalDir, "sdk_logs_${System.currentTimeMillis()}.zip")

		try {
			ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { out ->
				logFiles.forEach { file ->
					FileInputStream(file).use { input ->
						val entry = ZipEntry(file.name)
						out.putNextEntry(entry)

						input.copyTo(out)
						out.closeEntry()
					}
				}
			}
			return zipFile
		} catch (e: IOException) {
			Log.e(logTag, "Failed to zip logs", e)
			return null
		}
	}

	fun shareZippedLogs(context: Context) {
		val zipFile = exportToExternalZipped(context) ?: return

		val uri = FileProvider.getUriForFile(
			context,
			"${context.packageName}.provider",
			zipFile
		)

		val intent = Intent(Intent.ACTION_SEND).apply {
			type = "application/zip"
			putExtra(Intent.EXTRA_STREAM, uri)
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			putExtra(Intent.EXTRA_SUBJECT, "SDK Logs")
		}

		context.startActivity(Intent.createChooser(intent, "Share logs via"))
	}



	fun shareLogs(context: Context) {
		val exportedFiles = SdkLogger.exportToExternal(context)
		if (exportedFiles.isEmpty()) return

		val uris = exportedFiles.map {
			FileProvider.getUriForFile(context, "${context.packageName}.provider", it)
		}

		val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
			type = "text/plain"
			putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			putExtra(Intent.EXTRA_SUBJECT, "YourSdk Logs")
		}

		context.startActivity(Intent.createChooser(intent, "Share logs"))
	}

	fun emailZippedLogs(context: Context, supportEmail: String = "support@yoursdk.com") {
		val zipFile = exportToExternalZipped(context) ?: return

		val uri = FileProvider.getUriForFile(
			context,
			"${context.packageName}.provider",
			zipFile
		)

		val emailIntent = Intent(Intent.ACTION_SEND).apply {
			type = "application/zip"
			putExtra(Intent.EXTRA_EMAIL, arrayOf(supportEmail))
			putExtra(Intent.EXTRA_SUBJECT, "SDK Logs for Review")
			putExtra(Intent.EXTRA_TEXT, "Please find attached the SDK log file for troubleshooting.")
			putExtra(Intent.EXTRA_STREAM, uri)
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		}

		context.startActivity(Intent.createChooser(emailIntent, "Send logs via email"))
	}


}
