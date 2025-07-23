package com.fdeng.sdkhelper.logging

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/*
 * Created by fdeng on 2025-07-23
 * Handles permission requests and callbacks for exports requiring external access
 */

object SdkLogPermissionHelper {
	private const val REQUEST_CODE_EXPORT_LOGS = 1727
	private var exportCallback: ((Boolean, java.io.File?) -> Unit)? = null

	fun requestAndExportPublicLogs(
		activity: Activity,
		callback: (Boolean, java.io.File?) -> Unit
	) {
		exportCallback = callback
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q ||
			ContextCompat.checkSelfPermission(
				activity,
				Manifest.permission.WRITE_EXTERNAL_STORAGE
			) == PackageManager.PERMISSION_GRANTED
		) {
			val file = SdkLogExporter.exportToExternalZippedPublic(activity)
			callback(file != null, file)
		} else {
			ActivityCompat.requestPermissions(
				activity,
				arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
				REQUEST_CODE_EXPORT_LOGS
			)
		}
	}

	fun onRequestPermissionsResult(
		requestCode: Int,
		permissions: Array<out String>,
		grantResults: IntArray,
		context: Context
	) {
		if (requestCode == REQUEST_CODE_EXPORT_LOGS) {
			if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				val file = SdkLogExporter.exportToExternalZippedPublic(context)
				exportCallback?.invoke(file != null, file)
			} else {
				exportCallback?.invoke(false, null)
			}
			exportCallback = null
		}
	}
}