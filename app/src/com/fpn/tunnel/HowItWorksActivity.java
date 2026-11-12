package com.fpn.tunnel;

import android.app.Activity;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;

/**
 * "How it works" — the explainer graphic (assets/how-it-works-fa.png,
 * Persian) shown full-screen from Settings. Pure display; the image
 * ships in the APK so it works offline. 4.4.0: app furniture (back
 * row, localized title + caption) around the same graphic.
 */
public class HowItWorksActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(0xFF0A1122);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_main);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 20));
        root.addView(Ui.backRow(this));
        TextView title = new TextView(this);
        title.setText(R.string.how_title);
        title.setTextColor(Ui.HI);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = Ui.dp(this, 10);
        root.addView(title, tp);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
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
        page.addView(img, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView caption = new TextView(this);
        caption.setText(R.string.how_caption);
        caption.setTextColor(Ui.MID);
        caption.setTextSize(13.5f);
        caption.setLineSpacing(0, 1.4f);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.topMargin = Ui.dp(this, 14);
        page.addView(caption, cp);
        scroll.addView(page);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        sp.topMargin = Ui.dp(this, 14);
        root.addView(scroll, sp);
        setContentView(root);
    }
}
