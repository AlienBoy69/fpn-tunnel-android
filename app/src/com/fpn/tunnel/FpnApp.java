package com.fpn.tunnel;

import android.app.Application;

/**
 * Process entry point: installs the Fix P crash reporter (queue a
 * sanitized crash report, send it later through the tunnel) and tries
 * a flush of anything queued by a previous run.
 */
public class FpnApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ErrorReporter.installCrashHandler(this);
        ErrorReporter.flushAsync(this);
    }
}
