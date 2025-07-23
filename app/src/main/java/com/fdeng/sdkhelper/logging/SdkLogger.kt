package com.fdeng.sdkhelper.logging

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Centralized logger for SDK operations:
 * - Console logging with levels
 * - Optional file logging with daily rotation
 * - Log export and sharing
 */
object SdkLogger {

	/** Supported log levels */
	enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR, NONE }

	/** Listener for custom log actions */
	interface LogListener {
		fun onLog(level: Level, tag: String, message: String)
	}

	// -- Configuration --
	private const val DEFAULT_TAG = "YourSdk"
	private var logTag = DEFAULT_TAG
	private var isInternalBuild = false
	private var currentLevel = Level.WARN
	private var listener: LogListener? = null

	// -- File Logging --
	private var isFileLoggingEnabled = false
	private var logDir: File? = null
	private var logFile: File? = null
	private var bufferedWriter: BufferedWriter? = null
	private var currentLogDate = ""
	private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
	private val filenameDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
	private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	// -- Public Setup API --

	/** Enable/disable internal build log visibility */
	fun setInternalBuild(isInternal: Boolean) { isInternalBuild = isInternal }

	/** Set minimum log level for output */
	fun setLogLevel(level: Level) { currentLevel = level }

	/** Set custom tag for log output */
	fun setLogTag(tag: String) { if (tag.isNotBlank()) logTag = tag }

	/** Register/unregister a log listener */
	fun setLogListener(logListener: LogListener?) { listener = logListener }

	/**
	 * Enable or disable file logging.
	 * Creates log directory if enabling.
	 */
	fun enableFileLogging(context: Context, enable: Boolean) {
		isFileLoggingEnabled = enable
		if (enable) {
			logDir = File(context.filesDir, "logs").apply { mkdirs() }
			rotateLogFileIfNeeded()
		} else {
			closeLogWriter()
		}
	}

	// -- Logging Methods --

	fun v(message: String) = log(Level.VERBOSE, message)
	fun d(message: String) = log(Level.DEBUG, message)
	fun i(message: String) = log(Level.INFO, message)
	fun w(message: String) = log(Level.WARN, message)
	fun e(message: String) = log(Level.ERROR, message)

	/**
	 * Main logging function.
	 * Writes to console and file (if enabled), and notifies listener.
	 */
	private fun log(level: Level, message: String) {
		if (!shouldLog(level)) return
		val formatted = formatMessage(level, message)
		when (level) {
			Level.VERBOSE -> Log.v(logTag, formatted)
			Level.DEBUG   -> Log.d(logTag, formatted)
			Level.INFO    -> Log.i(logTag, formatted)
			Level.WARN    -> Log.w(logTag, formatted)
			Level.ERROR   -> Log.e(logTag, formatted)
			else -> {}
		}
		if (isFileLoggingEnabled) writeToFile(formatted)
		listener?.onLog(level, logTag, message)
	}

	/** True if the message should be logged at this level */
	private fun shouldLog(level: Level): Boolean =
		(level != Level.VERBOSE || isInternalBuild) &&
				level.ordinal >= currentLevel.ordinal &&
				currentLevel != Level.NONE

	/** Formats log message with timestamp and level */
	private fun formatMessage(level: Level, message: String) =
		"${timestampFormat.format(Date())} [${level.name}] $message"

	// -- File Logging Internals --

	/** Rotates daily log file if needed (runs in IO scope) */
	private fun rotateLogFileIfNeeded() = logScope.launch {
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
				Log.e(logTag, "Failed to create daily log file", e)
			}
		}
	}

	/** Writes message to file (uses coroutine for IO) */
	private fun writeToFile(message: String) = logScope.launch {
		try {
			rotateLogFileIfNeeded()
			bufferedWriter?.apply {
				write("$message\n")
			}
		} catch (e: IOException) {
			Log.e(logTag, "File write failed", e)
			restartWriter()
		}
	}

	/** Restarts file writer, for error recovery */
	private fun restartWriter() {
		closeLogWriter()
		rotateLogFileIfNeeded()
	}

	/** Closes the log writer gracefully */
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

	/** Flushes log file output */
	fun flush() = logScope.launch { try { bufferedWriter?.flush() } catch (_: IOException) {} }

	/** Clears the current log file */
	fun clearLogFile() = logScope.launch {
		closeLogWriter()
		logFile?.delete()
		rotateLogFileIfNeeded()
	}

	/** Stops all logging and cancels IO scope */
	fun shutdown() {
		closeLogWriter()
		logScope.cancel()
	}

	/** Returns current log file (if exists) */
	fun getLogFile(): File? = logFile

	// -- Export/Sharing Helpers --

	/**
	 * Copies all log files to external app-private documents directory.
	 * @return List of exported log files (may be empty)
	 */
	fun exportToExternal(context: Context): List<File> {
		val files = logDir?.listFiles { _, name -> name.startsWith("sdk_log_") && name.endsWith(".txt") } ?: return emptyList()
		val externalDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "YourSdkLogs").apply { mkdirs() }
		return files.mapNotNull { file ->
			try {
				val outFile = File(externalDir, file.name)
				file.copyTo(outFile, overwrite = true)
				outFile
			} catch (_: IOException) { null }
		}
	}

	/**
	 * Zips all log files and exports to external app-private documents directory.
	 * @return Zip file or null on error
	 */
	fun exportToExternalZipped(context: Context): File? {
		val logFiles = logDir?.listFiles { _, name -> name.startsWith("sdk_log_") && name.endsWith(".txt") } ?: return null
		if (logFiles.isEmpty()) return null
		val externalDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "YourSdkLogs").apply { mkdirs() }
		val zipFile = File(externalDir, "sdk_logs_${System.currentTimeMillis()}.zip")
		return zipFiles(logFiles, zipFile)
	}

	/**
	 * Zips log files to public Downloads/YourSdkLogs for sharing outside app sandbox.
	 * @return Zip file or null on error
	 */
	fun exportToExternalZippedPublic(context: Context): File? {
		val logFiles = logDir?.listFiles { _, name -> name.startsWith("sdk_log_") && name.endsWith(".txt") } ?: return null
		if (logFiles.isEmpty()) return null
		val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
		val zipFile = File(File(downloads, "YourSdkLogs").apply { mkdirs() }, "sdk_logs_${System.currentTimeMillis()}.zip")
		return zipFiles(logFiles, zipFile)
	}

	/**
	 * Helper to zip files to destination.
	 * @return Zip file or null on error
	 */
	private fun zipFiles(files: Array<File>, zipFile: File): File? = try {
		ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { out ->
			files.forEach { file ->
				FileInputStream(file).use { input ->
					out.putNextEntry(ZipEntry(file.name))
					input.copyTo(out)
					out.closeEntry()
				}
			}
		}
		zipFile
	} catch (_: IOException) { null }

	// -- Permission & Export Request Handling --

	private const val REQUEST_CODE_EXPORT_LOGS = 1727
	private var exportCallback: ((Boolean, File?) -> Unit)? = null

	/**
	 * Request permission and export logs to public Downloads folder.
	 * Calls callback(success, file) when done.
	 */
	fun requestAndExportPublicLogs(activity: Activity, callback: (Boolean, File?) -> Unit) {
		exportCallback = callback
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
			ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
			callback(exportToExternalZippedPublic(activity) != null, exportToExternalZippedPublic(activity))
		} else {
			ActivityCompat.requestPermissions(
				activity,
				arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
				REQUEST_CODE_EXPORT_LOGS
			)
		}
	}

	/**
	 * Should be called from Activity's onRequestPermissionsResult.
	 * Handles log export after permission grant.
	 */
	fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray, context: Context) {
		if (requestCode == REQUEST_CODE_EXPORT_LOGS) {
			if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				exportCallback?.invoke(exportToExternalZippedPublic(context) != null, exportToExternalZippedPublic(context))
			} else {
				exportCallback?.invoke(false, null)
			}
			exportCallback = null
		}
	}

	// -- Sharing & Email --

	/**
	 * Shares zipped logs using system share sheet.
	 */
	fun shareZippedLogs(context: Context) {
		val zipFile = exportToExternalZipped(context) ?: return
		val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", zipFile)
		val intent = Intent(Intent.ACTION_SEND).apply {
			type = "application/zip"
			putExtra(Intent.EXTRA_STREAM, uri)
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			putExtra(Intent.EXTRA_SUBJECT, "SDK Logs")
		}
		context.startActivity(Intent.createChooser(intent, "Share logs via"))
	}

	/**
	 * Shares all exported log files (uncompressed) using system share sheet.
	 */
	fun shareLogs(context: Context) {
		val exportedFiles = exportToExternal(context)
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

	/**
	 * Shares zipped logs via email to support address.
	 */
	fun emailZippedLogs(context: Context, supportEmail: String = "support@yoursdk.com") {
		val zipFile = exportToExternalZipped(context) ?: return
		val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", zipFile)
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