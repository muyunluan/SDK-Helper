package com.fdeng.sdkhelper.logging

import android.content.Context
import kotlinx.coroutines.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

/*
 * Created by fdeng on 2025-07-23
 * Handles file I/O, rotation, and flush/clear operations
 */

object SdkLogFileManager {

	private var isFileLoggingEnabled = false
	private var logDir: File? = null
	private var logFile: File? = null
	private var writer: BufferedWriter? = null
	private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
	private val filenameDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
	private var currentLogDate: String = ""
	private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	fun enableFileLogging(context: Context, enabled: Boolean) {
		isFileLoggingEnabled = enabled
		if (enabled) {
			logDir = File(context.filesDir, "logs").apply { mkdirs() }
			rotateLogFileIfNeeded()
		} else {
			closeLogWriter()
		}
	}

	fun writeLog(level: SdkLogger.Level, tag: String, message: String) {
		if (!isFileLoggingEnabled) return
		val formatted = "${timestampFormat.format(Date())} [${level.name}] [$tag] $message"
		logScope.launch {
			rotateLogFileIfNeeded()
			writer?.apply {
				write(formatted)
				write("\n")
				flush()
			}
		}
	}

	private fun rotateLogFileIfNeeded() {
		val today = filenameDateFormat.format(Date())
		if (today != currentLogDate || writer == null) {
			closeLogWriter()
			currentLogDate = today
			logFile = logDir?.let { File(it, "sdk_log_$today.txt") }
			try {
				writer = logFile?.let { BufferedWriter(FileWriter(it, true)) }
				writer?.write("---- LOG STARTED ${timestampFormat.format(Date())} ----\n")
				writer?.flush()
			} catch (_: IOException) {
				writer = null
			}
		}
	}

	private fun closeLogWriter() {
		try {
			writer?.apply {
				write("---- LOG CLOSED ${timestampFormat.format(Date())} ----\n")
				flush()
				close()
			}
		} catch (_: IOException) {}
		writer = null
	}

	fun flush() {
		logScope.launch {
			try { writer?.flush() } catch (_: IOException) {}
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

	fun getLogDir(): File? = logDir
	fun getLogFile(): File? = logFile
}