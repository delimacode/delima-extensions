package com.delima.archive

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArchivePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ArchiveProvider())
    }
}
