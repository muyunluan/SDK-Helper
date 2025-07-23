package com.fdeng.sdkhelper.logging

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * Created by fdeng on 2025-07-23
 * Handles exporting logs, zipping, and sharing/emailing files
 */

object SdkLogExporter {

	fun exportToExternal(context: Context): List<File> {
		val logDir = SdkLogFileManager.getLogDir() ?: return emptyList()
		val internalFiles = logDir.listFiles { _, name ->
			name.startsWith("sdk_log_") && name.endsWith(".txt")
		} ?: return emptyList()

		val externalDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "YourSdkLogs").apply { mkdirs() }

		return internalFiles.mapNotNull { file ->
			try {
				val outFile = File(externalDir, file.name)
				file.copyTo(outFile, overwrite = true)
				outFile
			} catch (_: IOException) { null }
		}
	}

	fun exportToExternalZipped(context: Context): File? {
		val logDir = SdkLogFileManager.getLogDir() ?: return null
		val logFiles = logDir.listFiles { _, name -> name.startsWith("sdk_log_") && name.endsWith(".txt") } ?: return null
		if (logFiles.isEmpty()) return null
		val externalDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "YourSdkLogs").apply { mkdirs() }
		val zipFile = File(externalDir, "sdk_logs_${System.currentTimeMillis()}.zip")
		return zipFiles(logFiles, zipFile)
	}

	fun exportToExternalZippedPublic(context: Context): File? {
		val logDir = SdkLogFileManager.getLogDir() ?: return null
		val logFiles = logDir.listFiles { _, name -> name.startsWith("sdk_log_") && name.endsWith(".txt") } ?: return null
		if (logFiles.isEmpty()) return null
		val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
		val dir = File(downloads, "YourSdkLogs").apply { mkdirs() }
		val zipFile = File(dir, "sdk_logs_${System.currentTimeMillis()}.zip")
		return zipFiles(logFiles, zipFile)
	}

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

	fun emailZippedLogs(context: Context, supportEmail: String) {
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