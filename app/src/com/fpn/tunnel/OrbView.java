package com.fpn.tunnel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Fix O — the connect control's centerpiece: a low-poly FOX head
 * (Firefox's animal) drawn with the framework Canvas only. The facet
 * layout and colors follow the project's reference render
 * (fpn-how-it-works/fox-preview.png): orange facet ramp, cream muzzle,
 * dark inner ears and nose, teal almond eyes — over a tilted orbit
 * ring with particles and a warm glow halo.
 *
 *   disconnected: desaturated, dim, slow sway; eyes dark
 *   connecting:   colors warm up, sway and orbits speed up; eyes amber
 *   connected:    full color, warm pulsing GLOW halo behind the head,
 *                 eyes light up teal, a shine sweep crosses the facets
 *
 * Animation: gentle breathing bob + pseudo-3D sway (rotation/skew, so
 * the facets shimmer), an occasional ear twitch. Runs only while
 * started (MainActivity: onResume/onPause) at ~30 fps. Paths and
 * paints are precomputed in onSizeChanged; onDraw allocates nothing.
 */
public class OrbView extends View {
    public static final int STATE_OFF = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_ON = 2;

    private static final long FRAME_MS = 33;
    private static final int PARTICLES = 12;

    // Reference-render palette (1100x1100 canvas, center 550,550).
    private static final int O_DARK = Color.rgb(198, 92, 22);
    private static final int O_MID = Color.rgb(232, 116, 32);
    private static final int O_LIGHT = Color.rgb(246, 148, 52);
    private static final int O_PALE = Color.rgb(255, 178, 92);
    private static final int CREAM = Color.rgb(255, 240, 220);
    private static final int CREAM_D = Color.rgb(243, 222, 196);
    private static final int INNER = Color.rgb(146, 64, 44);
    private static final int DARK = Color.rgb(38, 30, 34);
    private static final int TEAL = Color.rgb(64, 224, 178);

    /** Right-half facets in reference pixels; the left half is mirrored. */
    private static final float[][][] FACETS = {
            {{648, 330}, {742, 392}, {700, 560}, {610, 500}},   // temple
            {{550, 320}, {648, 330}, {610, 500}, {550, 520}},   // forehead
            {{550, 320}, {610, 500}, {550, 520}},               // forehead shine
            {{610, 500}, {700, 560}, {648, 716}, {548, 640}},   // cheek outer
            {{550, 520}, {610, 500}, {548, 640}, {550, 668}},   // cheek inner
            {{550, 668}, {548, 640}, {566, 742}, {550, 792}},   // muzzle
            {{548, 640}, {648, 716}, {566, 742}},               // jaw
            {{550, 792}, {566, 742}, {550, 858}},               // chin sliver
    };
    private static final int[] FACET_COLORS = {
            O_DARK, O_MID, O_PALE, O_LIGHT, O_PALE, CREAM, CREAM_D, CREAM_D};

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path[] facetPaths = new Path[FACETS.length];
    private final Path earOutR = new Path(), earInR = new Path();
    private final Path earOutL = new Path(), earInL = new Path();
    private final Path nosePath = new Path(), silhouette = new Path();
    private final Path eyeR = new Path(), eyeL = new Path();
    private final RectF ringRect = new RectF();
    private final float[] pPhase = new float[PARTICLES];
    private final float[] pSpeed = new float[PARTICLES];

    private int state = STATE_OFF;
    private boolean animating = false;
    private float cx, cy, unit; // unit = px per reference pixel
    private long startMs, lastDrawMs;
    private double phaseBob, phaseSway, phaseRing;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!animating) return;
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    public OrbView(Context ctx) { super(ctx); init(); }
    public OrbView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }

    private void init() {
        for (int i = 0; i < facetPaths.length; i++) facetPaths[i] = new Path();
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2.4f);
        ringPaint.setColor(Color.argb(130, 120, 180, 255));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < PARTICLES; i++) {
            pPhase[i] = rnd.nextFloat() * 6.2832f;
            pSpeed[i] = 0.15f + rnd.nextFloat() * 0.3f;
        }
    }

    public void setState(int s) {
        if (state == s) return;
        state = s;
        buildHalo();
        invalidate();
    }

    public int getState() { return state; }

    public void startAnim() {
        if (animating) return;
        animating = true;
        startMs = System.currentTimeMillis();
        lastDrawMs = startMs;
        post(tick);
    }

    public void stopAnim() {
        animating = false;
        removeCallbacks(tick);
    }

    private float px(float refX) { return cx + (refX - 550f) * unit; }
    private float py(float refY) { return cy + (refY - 550f) * unit; }

    private void buildPath(Path p, float[][] pts) {
        p.reset();
        for (int i = 0; i < pts.length; i++) {
            if (i == 0) p.moveTo(px(pts[i][0]), py(pts[i][1]));
            else p.lineTo(px(pts[i][0]), py(pts[i][1]));
        }
        p.close();
    }

    private void buildEye(Path p, float centerX) {
        float[][] e = {{centerX - 46, 556}, {centerX + 46, 544},
                {centerX + 30, 578}, {centerX - 30, 580}};
        buildPath(p, e);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cx = w / 2f;
        cy = h / 2f;
        unit = Math.min(w, h) / 1100f; // reference canvas scale
        for (int i = 0; i < FACETS.length; i++) buildPath(facetPaths[i], FACETS[i]);
        buildPath(earOutR, new float[][]{{648, 130}, {742, 392}, {566, 330}});
        buildPath(earInR, new float[][]{{664, 205}, {712, 362}, {602, 322}});
        buildPath(earOutL, new float[][]{{452, 130}, {358, 392}, {534, 330}});
        buildPath(earInL, new float[][]{{436, 205}, {388, 362}, {498, 322}});
        buildPath(nosePath, new float[][]{{508, 700}, {592, 700}, {550, 756}});
        buildEye(eyeR, 618);
        buildEye(eyeL, 482);
        // Head silhouette (for the shine-sweep clip): outline, both halves.
        buildPath(silhouette, new float[][]{
                {648, 130}, {742, 392}, {700, 560}, {648, 716}, {566, 742},
                {550, 858}, {534, 742}, {452, 716}, {400, 560}, {358, 392},
                {452, 130}, {534, 330}, {550, 320}, {566, 330}});
        float rx = Math.min(w, h) * 0.44f, ry = Math.min(w, h) * 0.135f;
        ringRect.set(cx - rx, cy - ry + Math.min(w, h) * 0.02f,
                cx + rx, cy + ry + Math.min(w, h) * 0.02f);
        buildHalo();
    }

    private void buildHalo() {
        if (unit <= 0) return;
        float r = 500f * unit;
        int warm = state == STATE_ON ? Color.argb(110, 255, 140, 40)
                : state == STATE_CONNECTING ? Color.argb(60, 255, 170, 60)
                : Color.argb(26, 120, 150, 200);
        int teal = state == STATE_ON ? Color.argb(80, 64, 224, 178)
                : Color.argb(0, 64, 224, 178);
        haloPaint.setShader(new RadialGradient(cx, cy, r,
                new int[]{teal, warm, Color.TRANSPARENT},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    /** Modulate a palette color for the current state + a light shimmer. */
    private int shade(int base, double shimmer) {
        int r = Color.red(base), g = Color.green(base), b = Color.blue(base);
        if (state == STATE_OFF) {
            int lum = (r * 30 + g * 59 + b * 11) / 100;
            r = (int) ((r * 0.35 + lum * 0.65) * 0.55);
            g = (int) ((g * 0.35 + lum * 0.65) * 0.58);
            b = (int) ((b * 0.35 + lum * 0.65) * 0.75);
        } else if (state == STATE_CONNECTING) {
            r = (int) (r * 0.92); g = (int) (g * 0.88); b = (int) (b * 0.8);
        } else {
            double f = 1.0 + shimmer;
            r = Math.min(255, (int) (r * f));
            g = Math.min(255, (int) (g * f));
            b = Math.min(255, (int) (b * f));
        }
        return Color.rgb(r, g, b);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (unit <= 0) return;
        long now = System.currentTimeMillis();
        double dt = Math.min(0.1, (now - lastDrawMs) / 1000.0);
        lastDrawMs = now;
        double speedMul = state == STATE_ON ? 1.5 : state == STATE_CONNECTING ? 2.8 : 0.55;
        phaseBob += dt * 1.5 * (state == STATE_OFF ? 0.6 : 1.0);
        phaseSway += dt * 0.65 * speedMul;
        phaseRing += dt * 0.12 * speedMul;
        double t = (now - startMs) / 1000.0;

        // Glow halo (pulses while connected).
        float pulse = state == STATE_ON ? 1f + 0.05f * (float) Math.sin(t * 2.1) : 1f;
        canvas.save();
        canvas.scale(pulse, pulse, cx, cy);
        canvas.drawCircle(cx, cy, 500f * unit, haloPaint);
        canvas.restore();

        // Orbit ring + particles behind the head.
        canvas.save();
        canvas.rotate((float) Math.sin(phaseRing) * 3.5f, cx, cy);
        canvas.drawOval(ringRect, ringPaint);
        for (int i = 0; i < PARTICLES; i++) {
            double a = pPhase[i] + t * pSpeed[i] * speedMul;
            float x = cx + (float) Math.cos(a) * ringRect.width() / 2f;
            float y = ringRect.centerY() + (float) Math.sin(a) * ringRect.height() / 2f;
            particlePaint.setColor(state == STATE_ON
                    ? Color.argb(230, 140, 235, 205) : Color.argb(160, 150, 205, 250));
            canvas.drawCircle(x, y, 3.1f, particlePaint);
        }
        canvas.restore();

        // Fox head: breathing bob + pseudo-3D sway.
        canvas.save();
        canvas.translate(0, (float) Math.sin(phaseBob) * 90f * unit * 0.35f);
        float breathe = 1f + 0.012f * (float) Math.sin(phaseBob);
        canvas.scale(breathe, breathe, cx, cy);
        canvas.rotate((float) Math.sin(phaseSway) * 2.6f, cx, cy);
        canvas.skew((float) Math.sin(phaseSway * 0.9) * 0.02f, 0f);

        // Ears (left ear twitches now and then).
        double cyc = t % 4.7;
        float twitch = (state != STATE_OFF && cyc < 0.26)
                ? (float) Math.sin(cyc / 0.26 * Math.PI) * 6f : 0f;
        canvas.save();
        canvas.rotate(twitch, px(534), py(330));
        fillPaint.setColor(shade(O_DARK, 0.02));
        canvas.drawPath(earOutL, fillPaint);
        fillPaint.setColor(shade(INNER, 0));
        canvas.drawPath(earInL, fillPaint);
        canvas.restore();
        fillPaint.setColor(shade(O_DARK, 0.02));
        canvas.drawPath(earOutR, fillPaint);
        fillPaint.setColor(shade(INNER, 0));
        canvas.drawPath(earInR, fillPaint);

        // Face facets: right half, then mirrored left half.
        for (int pass = 0; pass < 2; pass++) {
            canvas.save();
            if (pass == 1) canvas.scale(-1f, 1f, cx, cy);
            for (int i = 0; i < facetPaths.length; i++) {
                double shimmer = state == STATE_ON
                        ? 0.07 * Math.sin(t * 1.3 + i * 0.9) : 0;
                fillPaint.setColor(shade(FACET_COLORS[i], shimmer));
                canvas.drawPath(facetPaths[i], fillPaint);
            }
            canvas.restore();
        }

        // Eyes: lit teal when connected, amber while connecting, dark off.
        fillPaint.setColor(state == STATE_ON ? TEAL
                : state == STATE_CONNECTING ? Color.rgb(255, 190, 60) : DARK);
        canvas.drawPath(eyeR, fillPaint);
        canvas.drawPath(eyeL, fillPaint);

        // Nose + mouth line.
        fillPaint.setColor(shade(DARK, 0));
        canvas.drawPath(nosePath, fillPaint);
        linePaint.setColor(shade(DARK, 0));
        linePaint.setStrokeWidth(7f * unit);
        canvas.drawLine(px(550), py(756), px(550), py(786), linePaint);

        // Shine sweep across the facets while connected.
        if (state == STATE_ON) {
            float sweep = (float) ((t % 3.4) / 3.4);
            canvas.save();
            canvas.clipPath(silhouette);
            canvas.translate(cx - 420f * unit + sweep * 840f * unit, cy);
            canvas.rotate(-22f);
            fillPaint.setColor(Color.argb(46, 255, 255, 255));
            canvas.drawRect(-40f * unit, -560f * unit, 40f * unit, 560f * unit, fillPaint);
            canvas.restore();
        }
        canvas.restore();
    }
}
