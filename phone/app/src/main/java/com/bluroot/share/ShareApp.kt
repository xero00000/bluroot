package com.bluroot.share

import android.app.Application
import com.topjohnwu.superuser.Shell

class ShareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Shell.setDefaultBuilder(
            Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR)
        )
    }
}
