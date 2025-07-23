package com.fdeng.sdkhelper.logging

import android.content.Context

/*
 * Created by fdeng on 2025-07-23
 * Handles log levels, console logging, listener callbacks, and delegates file operations
 */
object SdkLogger {

	enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR, NONE }
	interface LogListener {
		fun onLog(level: Level, tag: String, message: String)
	}

	private var logTag = "YourSdk"
	private var currentLevel = Level.WARN
	private var isInternalBuild = false
	private var listener: LogListener? = null

	fun setInternalBuild(isInternal: Boolean) { isInternalBuild = isInternal }
	fun setLogLevel(level: Level) { currentLevel = level }
	fun setLogTag(tag: String) { logTag = tag }
	fun setLogListener(logListener: LogListener?) { listener = logListener }

	fun v(msg: String) = log(Level.VERBOSE, msg)
	fun d(msg: String) = log(Level.DEBUG, msg)
	fun i(msg: String) = log(Level.INFO, msg)
	fun w(msg: String) = log(Level.WARN, msg)
	fun e(msg: String) = log(Level.ERROR, msg)

	private fun log(level: Level, message: String) {
		if (!shouldLog(level)) return
		val formatted = "[${level.name}] $message"
		when (level) {
			Level.VERBOSE -> android.util.Log.v(logTag, formatted)
			Level.DEBUG   -> android.util.Log.d(logTag, formatted)
			Level.INFO    -> android.util.Log.i(logTag, formatted)
			Level.WARN    -> android.util.Log.w(logTag, formatted)
			Level.ERROR   -> android.util.Log.e(logTag, formatted)
			else -> {}
		}
		SdkLogFileManager.writeLog(level, logTag, message)
		listener?.onLog(level, logTag, message)
	}

	private fun shouldLog(level: Level): Boolean {
		if (level == Level.VERBOSE && !isInternalBuild) return false
		return level.ordinal >= currentLevel.ordinal && currentLevel != Level.NONE
	}

	fun enableFileLogging(context: Context, enabled: Boolean) {
		SdkLogFileManager.enableFileLogging(context, enabled)
	}

	fun flush() = SdkLogFileManager.flush()
	fun clearLogFile() = SdkLogFileManager.clearLogFile()
	fun shutdown() = SdkLogFileManager.shutdown()

	// Delegates for sharing, exporting, emailing logs
	fun shareLogs(context: Context) = SdkLogExporter.shareLogs(context)
	fun shareZippedLogs(context: Context) = SdkLogExporter.shareZippedLogs(context)
	fun emailZippedLogs(context: Context, supportEmail: String = "support@yoursdk.com") =
		SdkLogExporter.emailZippedLogs(context, supportEmail)

	fun exportToExternal(context: Context) = SdkLogExporter.exportToExternal(context)
	fun exportToExternalZipped(context: Context) = SdkLogExporter.exportToExternalZipped(context)
	fun exportToExternalZippedPublic(context: Context) = SdkLogExporter.exportToExternalZippedPublic(context)

	fun requestAndExportPublicLogs(
		activity: android.app.Activity,
		callback: (Boolean, java.io.File?) -> Unit
	) {
		SdkLogPermissionHelper.requestAndExportPublicLogs(activity, callback)
	}

	fun onRequestPermissionsResult(
		requestCode: Int,
		permissions: Array<out String>,
		grantResults: IntArray,
		context: Context
	) {
		SdkLogPermissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults, context)
	}
}