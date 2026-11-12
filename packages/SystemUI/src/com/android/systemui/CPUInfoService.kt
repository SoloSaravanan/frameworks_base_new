/*
 * Copyright (C) 2019-2025 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui

import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.android.systemui.res.R
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.WakefulnessLifecycle
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CPUInfoService @Inject constructor(
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    @Main private val handler: Handler
) : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var cpuInfoView: TextView
    private lateinit var configuration: Configuration
    private lateinit var coroutineScope: CoroutineScope

    private var readJob: kotlinx.coroutines.Job? = null
    private var observerRegistered = false
    private var cpuList: List<String> = emptyList()
    private var cpuTempDivider = 1
    private var cpuTempSensor = ""
    private var cpuDisplayString = ""

    private val fileMap = mutableMapOf<String, RandomAccessFile>()
    private val fileRetryAfter = mutableMapOf<String, Long>()
    private val unavailablePaths = mutableSetOf<String>()

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
    }

    private val wakefulnessObserver = object : WakefulnessLifecycle.Observer {
        override fun onStartedGoingToSleep() {
            stopReading()
        }

        override fun onStartedWakingUp() {
            startReading()
        }
    }

    override fun onCreate() {
        super.onCreate()
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        windowManager = getSystemService(WindowManager::class.java)!!
        configuration = resources.configuration
        layoutParams.y = getTopInset()

        cpuTempDivider = resources.getInteger(R.integer.config_cpuTempDivider)
        if (cpuTempDivider <= 0) {
            Log.w(TAG, "Invalid CPU temperature divider ($cpuTempDivider); using 1")
            cpuTempDivider = 1
        }
        cpuTempSensor = getString(R.string.config_cpuTempSensor)
        cpuDisplayString = getString(R.string.config_displayCpus)

        cpuList = parseCpuList(cpuDisplayString)

        cpuInfoView = TextView(this).apply {
            setBackgroundColor(ColorUtils.setAlphaComponent(Color.BLACK, BACKGROUND_ALPHA))
            setTextColor(Color.WHITE)
            val padding = resources.getDimensionPixelSize(R.dimen.fps_info_text_padding)
            setPadding(padding, padding, padding, padding)
        }
    }

    override fun onStartCommand(intent: Intent?, startId: Int, flags: Int): Int {
        if (!observerRegistered) {
            wakefulnessLifecycle.addObserver(wakefulnessObserver)
            observerRegistered = true
        }
        startReading()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (configuration.orientation != newConfig.orientation) {
            layoutParams.y = getTopInset()
            if (cpuInfoView.parent != null)
                windowManager.updateViewLayout(cpuInfoView, layoutParams)
        }
        configuration = newConfig
    }

    private fun getTopInset(): Int = windowManager.currentWindowMetrics
        .windowInsets.getInsets(WindowInsets.Type.statusBars()).top

    private fun parseCpuList(displayCpus: String): List<String> {
        return if (displayCpus.isNotBlank()) {
            displayCpus.split(",").filter { it.toIntOrNull() != null }
        } else {
            val path = "/sys/devices/system/cpu/present"
            val range = try {
                File(path).bufferedReader().use { it.readLine() }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to read CPU range: ${e.message}")
                null
            } ?: return listOf("0")

            return when {
                range.contains("-") -> {
                    val bounds = range.split("-").mapNotNull { it.toIntOrNull() }
                    if (bounds.size == 2) (bounds[0]..bounds[1]).map { it.toString() } else listOf("0")
                }
                range.contains(",") -> {
                    range.split(",").filter { it.toIntOrNull() != null }
                }
                else -> listOf("0")
            }
        }
    }

    private fun startReading() {
        readJob?.cancel()
        if (cpuInfoView.parent == null) windowManager.addView(cpuInfoView, layoutParams)
        readJob = coroutineScope.launch {
            while (isActive) {
                val builder = StringBuilder()
                val temp = if (cpuTempSensor.isNotBlank()) {
                    readLineFromRandomAccessFile(cpuTempSensor)?.toIntOrNull()?.div(cpuTempDivider)
                } else {
                    null
                }
                builder.append(temp?.let { "Temp: ${it}°C\n" } ?: "Temp: N/A\n")

                cpuList.forEach { cpu ->
                    val freqPath = "/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_cur_freq"
                    val govPath = "/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_governor"
                    val freq = readLineFromRandomAccessFile(freqPath)?.toIntOrNull()
                    val gov = readLineFromRandomAccessFile(govPath) ?: "N/A"
                    when {
                        freq == null -> builder.append("cpu$cpu: unavailable\n")
                        freq <= 0 -> builder.append("cpu$cpu: offline\n")
                        else -> builder.append("cpu$cpu: $gov ${freq / 1000}MHz\n")
                    }
                }

                val output = builder.toString().trim()
                handler.post {
                    cpuInfoView.text = output
                    cpuInfoView.visibility = View.VISIBLE
                }
                delay(MEASURE_INTERVAL_DEFAULT)
            }
        }
    }

    private fun stopReading() {
        readJob?.cancel()
        readJob = null
        removeViewIfNeeded()
    }

    private fun removeViewIfNeeded() {
        if (cpuInfoView.parent != null) {
            windowManager.removeViewImmediate(cpuInfoView)
        }
    }

    private fun readLineFromRandomAccessFile(path: String): String? {
        if (path.isBlank()) return null

        val now = SystemClock.elapsedRealtime()
        val file = fileMap[path] ?: run {
            if (now < (fileRetryAfter[path] ?: 0L)) return null
            try {
                RandomAccessFile(path, "r").also {
                    fileMap[path] = it
                    fileRetryAfter.remove(path)
                    unavailablePaths.remove(path)
                }
            } catch (e: IOException) {
                markPathUnavailable(path, now, e)
                return null
            } catch (e: SecurityException) {
                markPathUnavailable(path, now, e)
                return null
            }
        }
        return try {
            file.seek(0L)
            file.readLine().also {
                unavailablePaths.remove(path)
            }
        } catch (e: IOException) {
            fileMap.remove(path)?.let {
                try {
                    it.close()
                } catch (_: IOException) {}
            }
            markPathUnavailable(path, now, e)
            null
        }
    }

    private fun markPathUnavailable(path: String, now: Long, error: Exception) {
        fileRetryAfter[path] = now + NODE_RETRY_INTERVAL
        if (unavailablePaths.add(path)) {
            Log.w(TAG, "CPU info node unavailable: $path", error)
        }
    }

    override fun onDestroy() {
        stopReading()
        coroutineScope.cancel()
        fileMap.values.forEach {
            try { it.close() } catch (_: IOException) {}
        }
        fileMap.clear()
        if (observerRegistered) {
            wakefulnessLifecycle.removeObserver(wakefulnessObserver)
            observerRegistered = false
        }
        removeViewIfNeeded()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        private const val TAG = "CPUInfoService"
        private const val MEASURE_INTERVAL_DEFAULT = 1000L
        private const val NODE_RETRY_INTERVAL = 5000L
        private const val BACKGROUND_ALPHA = 120
    }
}
