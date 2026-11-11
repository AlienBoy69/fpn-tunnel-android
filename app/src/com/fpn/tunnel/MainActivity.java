package com.fpn.tunnel;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * One screen: connect / disconnect the FPN whole-phone tunnel, plus a
 * location picker that tells the Termux engine (127.0.0.1:11081) which
 * country new connections should exit from.
 */
public class MainActivity extends Activity {
    private TextView status;

    private static final String[][] COUNTRIES = {
        {"Germany", "DE"}, {"United States", "US"}, {"United Kingdom", "GB"},
        {"France", "FR"}, {"Netherlands", "NL"}, {"Canada", "CA"},
        {"Japan", "JP"}, {"Australia", "AU"}, {"Argentina", "AR"},
        {"South Africa", "ZA"}, {"Austria", "AT"}, {"Belgium", "BE"},
        {"Brazil", "BR"}, {"Bulgaria", "BG"}, {"Chile", "CL"},
        {"Colombia", "CO"}, {"Denmark", "DK"}, {"Finland", "FI"},
        {"Ghana", "GH"}, {"Ireland", "IE"}, {"Italy", "IT"},
        {"Malaysia", "MY"}, {"Mexico", "MX"}, {"New Zealand", "NZ"},
        {"Norway", "NO"}, {"Peru", "PE"}, {"Philippines", "PH"},
        {"Portugal", "PT"}, {"Singapore", "SG"}, {"South Korea", "KR"},
        {"Spain", "ES"}, {"Sweden", "SE"}, {"Thailand", "TH"}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        lay.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(17);
        status.setText("FPN Tunnel — Firefox Private Network\nProxy: 127.0.0.1:11080 (Termux engine)");
        lay.addView(status);

        Button on = new Button(this);
        on.setText("Connect");
        on.setOnClickListener(v -> {
            Intent prep = VpnService.prepare(this);
            if (prep != null) startActivityForResult(prep, 1);
            else startTunnel();
        });
        lay.addView(on);

        Button off = new Button(this);
        off.setText("Disconnect");
        off.setOnClickListener(v -> {
            startService(new Intent(this, FpnVpnService.class).setAction("stop"));
            status.setText("Disconnect requested.");
        });
        lay.addView(off);

        final SharedPreferences prefs = getSharedPreferences("fpn", MODE_PRIVATE);
        final String saved = prefs.getString("country", "DE");

        TextView locLabel = new TextView(this);
        locLabel.setTextSize(15);
        locLabel.setText("Location (applies to new connections):");
        lay.addView(locLabel);

        final Spinner spin = new Spinner(this);
        List<String> names = new ArrayList<>();
        int sel = 0;
        for (int i = 0; i < COUNTRIES.length; i++) {
            names.add(COUNTRIES[i][0]);
            if (COUNTRIES[i][1].equals(saved)) sel = i;
        }
        spin.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        spin.setSelection(sel);
        spin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            String lastCode = saved;
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                final String code = COUNTRIES[pos][1];
                final String name = COUNTRIES[pos][0];
                prefs.edit().putString("country", code).apply();
                if (code.equals(lastCode)) return; // initial layout fire, or no change
                lastCode = code;
                new Thread(() -> {
                    String r = pushCountry(code);
                    runOnUiThread(() -> status.setText(
                        r != null ? ("Location set: " + name + " (" + code + ")")
                                  : ("Location saved: " + name + " (" + code + "). Engine not reachable; applies when the engine runs.")));
                }).start();
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        lay.addView(spin);

        setContentView(lay);
    }

    /** Best-effort: tell the engine which country to exit from. Returns its reply or null. */
    private String pushCountry(String code) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                "http://127.0.0.1:11081/country?c=" + code).openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK) startTunnel();
    }

    private void startTunnel() {
        final String code = getSharedPreferences("fpn", MODE_PRIVATE).getString("country", "DE");
        status.setText("Connecting…");
        new Thread(() -> {
            pushCountry(code); // best effort; the engine also remembers its last country
            try {
                Intent i = new Intent(this, FpnVpnService.class);
                startForegroundService(i);
                runOnUiThread(() -> status.setText("Service start requested. Tunnel should come up in seconds."));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Start FAILED: " + e));
            }
        }).start();
    }
}
