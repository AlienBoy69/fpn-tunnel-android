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
        // Pass resilience (DIAG-2026-11-16): keep a fresh Guardian
        // pass cached on disk while the process lives, so a connect
        // on a network that filters Mozilla's hosts can start from
        // the cache instead of minting a pass on the spot.
        FpnEngine.startPassPrefetcher(this);
    }
}
