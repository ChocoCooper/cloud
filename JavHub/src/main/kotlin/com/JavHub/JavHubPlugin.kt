package com.JavHub

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class JavHubPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(JavHubProvider())
    }
}
