package com.obdlogger.app

import com.obdlogger.core.SeriesStore

/** Rows of the current recording for the live monitor (filled by [LoggerService]). */
object LiveData {
    val store = SeriesStore()
}
