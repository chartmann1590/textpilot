package com.charles.messenger.feature.settings.feedback

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.charles.messenger.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DiagnosticsHelper {

    fun collect(context: Context): String {
        val appName = context.applicationInfo.loadLabel(context.packageManager).toString()
        val packageName = context.packageName
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE

        val brand = Build.BRAND
        val model = Build.MODEL
        val manufacturer = Build.MANUFACTURER
        val androidVersion = Build.VERSION.RELEASE
        val apiLevel = Build.VERSION.SDK_INT
        val locale = Locale.getDefault().toString()
        val timeZone = TimeZone.getDefault().id
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())

        val stat = StatFs(Environment.getDataDirectory().path)
        val freeStorage = formatBytes(stat.availableBlocksLong * stat.blockSizeLong)
        val totalStorage = formatBytes(stat.blockCountLong * stat.blockSizeLong)

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val freeMemory = formatBytes(memInfo.availMem)
        val totalMemory = formatBytes(memInfo.totalMem)

        return """
## Diagnostics

- App: $appName
- Package: $packageName
- Version: $versionName ($versionCode)
- Device: $brand $model
- Manufacturer: $manufacturer
- Android: $androidVersion / API $apiLevel
- Locale: $locale
- Time Zone: $timeZone
- Storage Free/Total: $freeStorage / $totalStorage
- Memory Free/Total: $freeMemory / $totalMemory
- Timestamp: $timestamp
        """.trimIndent()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        else -> "%.1f KB".format(bytes / 1024.0)
    }
}
