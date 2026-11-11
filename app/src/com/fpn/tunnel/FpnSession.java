package com.fpn.tunnel;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The signed-in Firefox account, stored as session.json in the app's
 * private files — the same {accountData:{...}} shape as a desktop
 * Firefox signedInUser.json, which is what the engine reads.
 */
public final class FpnSession {
    private FpnSession() {}

    public static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "session.json");
    }

    public static boolean exists(Context ctx) {
        File f = file(ctx);
        return f.isFile() && f.length() > 0;
    }

    public static void save(Context ctx, JSONObject account) throws Exception {
        JSONObject doc = new JSONObject().put("accountData", account);
        try (FileOutputStream o = ctx.openFileOutput("session.json", Context.MODE_PRIVATE)) {
            o.write(doc.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    public static String email(Context ctx) {
        try {
            FileInputStream in = new FileInputStream(file(ctx));
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            in.close();
            if (n <= 0) return null;
            JSONObject doc = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
            JSONObject acct = doc.optJSONObject("accountData");
            return acct != null ? acct.optString("email", null) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void clear(Context ctx) {
        try { file(ctx).delete(); } catch (Exception ignored) {}
    }
}
