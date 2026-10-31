package com.delima.lilik

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LilikPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LilikProvider())
    }
}