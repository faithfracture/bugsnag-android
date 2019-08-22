package com.bugsnag.android.mazerunner.scenarios

import android.content.Context
import com.bugsnag.android.*

internal class InternalReportScenario(config: Configuration,
                                      context: Context) : Scenario(config, context) {
    init {
        config.setAutoCaptureSessions(false)
    }

    override fun run() {
        super.run()
        sendInternalReport(RuntimeException("Whoops"), config, Bugsnag.getClient())
    }

}
