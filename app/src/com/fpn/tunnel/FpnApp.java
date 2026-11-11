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
        // Fix Q: copy-only migration of a legacy single session.json
        // into the multi-account store (account #1). Runs before any
        // component can read the session; idempotent.
        FpnSession.ensureMigrated(this);
        ErrorReporter.installCrashHandler(this);
        ErrorReporter.flushAsync(this);
    }
}
