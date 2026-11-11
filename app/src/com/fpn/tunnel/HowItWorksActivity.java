package com.fpn.tunnel;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;

/**
 * "How it works" — the explainer graphic (assets/how-it-works-fa.png,
 * Persian) shown full-screen from the main screen's button. Pure
 * display; the image ships in the APK so it works offline.
 */
public class HowItWorksActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0A1122);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        TextView title = new TextView(this);
        title.setText("How it works");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setPadding(pad, pad, pad, pad);
        root.addView(title);
        ScrollView scroll = new ScrollView(this);
        ImageView img = new ImageView(this);
        img.setAdjustViewBounds(true);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try {
            InputStream in = getAssets().open("how-it-works-fa.png");
            img.setImageBitmap(BitmapFactory.decodeStream(in));
            in.close();
        } catch (Exception e) {
            img.setImageResource(R.drawable.ic);
        }
        scroll.addView(img, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }
}
