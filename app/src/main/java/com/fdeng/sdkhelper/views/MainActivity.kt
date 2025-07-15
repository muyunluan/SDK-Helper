package com.fdeng.sdkhelper.views

import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.fdeng.sdkhelper.R
import com.fdeng.sdkhelper.logging.SdkLogger
class MainActivity : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContentView(R.layout.activity_main)
		ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
			val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
			v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
			insets
		}

        //SdkLogger.setInternalBuild(BuildConfig.DEBUG)
        SdkLogger.setLogLevel(SdkLogger.Level.DEBUG)
        SdkLogger.enableFileLogging(this@MainActivity, true)

		val button1 = findViewById<Button>(R.id.button1)
		val button2 = findViewById<Button>(R.id.button2)
		val button3 = findViewById<Button>(R.id.button3)
		val button4 = findViewById<Button>(R.id.button4)

		button1.setOnClickListener {
			// Code to execute when button1 is clicked
			SdkLogger.i("Button 1 Clicked")
			SdkLogger.flush()
		}

		button2.setOnClickListener {
			// Code to execute when button2 is clicked
			SdkLogger.exportToExternal(this@MainActivity)
		}

		button3.setOnClickListener {
			// Code to execute when button3 is clicked
			SdkLogger.emailZippedLogs(this@MainActivity, "fdengwork@gmail.com")
		}

		button4.setOnClickListener {
			// Code to execute when button4 is clicked
			SdkLogger.shutdown()
		}
	}
}