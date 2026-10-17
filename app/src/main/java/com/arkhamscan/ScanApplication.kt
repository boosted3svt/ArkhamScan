package com.arkhamscan

import android.app.Application

/** One Room connection manager per process, shared across activity recreation. */
open class ScanApplication : Application() {
    open val database: ScanDatabase by lazy { ScanDatabase.open(this) }
}
