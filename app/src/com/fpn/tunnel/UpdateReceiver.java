package com.fpn.tunnel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Receives PackageInstaller status callbacks for in-app updates. */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -99);
        AppUpdate.ulog(ctx, "installer status=" + status + " "
                + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(confirm);
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(ctx, "FPN Tunnel updated ✓", Toast.LENGTH_SHORT).show();
        } else if (status != -99) {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(ctx, "Update failed" + (msg != null ? ": " + msg : ""),
                    Toast.LENGTH_LONG).show();
        }
    }
}
