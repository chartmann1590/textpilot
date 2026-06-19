package com.charles.messenger.feature.settings.feedback

import android.content.Context
import android.content.SharedPreferences
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class BugReportRepo(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("feedback_bug_reports", Context.MODE_PRIVATE)

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, BugReport::class.java)
    private val adapter = moshi.adapter<List<BugReport>>(listType)

    fun getReports(): List<BugReport> = try {
        val json = prefs.getString("bug_reports_list", null)
        if (json == null) emptyList()
        else adapter.fromJson(json) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun saveReport(report: BugReport) {
        val list = getReports().toMutableList()
        val idx = list.indexOfFirst { it.number == report.number }
        if (idx >= 0) list[idx] = report else list.add(0, report)
        prefs.edit().putString("bug_reports_list", adapter.toJson(list)).apply()
    }

    fun updateReports(reports: List<BugReport>) {
        prefs.edit().putString("bug_reports_list", adapter.toJson(reports)).apply()
    }
}
