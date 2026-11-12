package com.fpn.tunnel;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Fix O — the connect control's centerpiece: the FOX head over a
 * tilted orbit ring with particles and a warm glow halo.
 *
 * 4.4.0: the head itself is the glossy 3D-style artwork
 * (R.drawable.fox_hero — an original gradient-shaded render, the
 * same art as the launcher icon), state-modulated with color
 * filters; the halo, ring, particles, breathing bob, sway and the
 * connected shine sweep are unchanged from the facet era. If the
 * bitmap cannot load, the original low-poly facet fox below draws
 * instead — same colors, same composition (1100-unit reference).
 *
 *   disconnected: desaturated, dim, slow sway
 *   connecting:   colors warm up, sway and orbits speed up
 *   connected:    full color, warm pulsing GLOW halo behind the
 *                 head, a shine sweep crosses the head
 *
 * Animation: gentle breathing bob + pseudo-3D sway (rotation/skew).
 * The facet fallback also keeps the occasional ear twitch. Runs only while
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
    private final Paint heroPaint = new Paint(
            Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap heroBitmap;
    private final RectF heroRect = new RectF();
    // 4.4.0 animation pass: perspective head-turn (Camera), the
    // connected reaction burst and the disconnect settle. All are
    // hardware-friendly canvas transforms of the poster — no raster
    // warping, no extra assets.
    private final Camera heroCamera = new Camera();
    private final Matrix camMatrix = new Matrix();
    private final Matrix heroMatrix = new Matrix();
    private final RectF heroSrcF = new RectF();
    private long burstStartMs = 0;   // →ON reaction (nod + glow flare)
    private long settleStartMs = 0;  // →OFF settle (sink + dim)
    private float settleYpx = 0f;    // residual sink while OFF
    private ColorMatrixColorFilter filterOff, filterConnecting;
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // The hero art carries a REAL alpha channel (its flat navy is
    // keyed out with a soft matte) and sits directly on the card —
    // no underlay, no pool, no rectangle of any kind behind it.
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
        // 4.4.0 hero art + its state filters (built once).
        try {
            heroBitmap = BitmapFactory.decodeResource(
                    getContext().getResources(), R.drawable.fox_hero);
            if (heroBitmap != null)
                heroSrcF.set(0, 0, heroBitmap.getWidth(), heroBitmap.getHeight());
        } catch (Exception e) {
            heroBitmap = null; // facet fallback draws instead
        }
        filterOff = stateFilter(0.25f, 0.55f, 0.60f, 0.78f);
        filterConnecting = stateFilter(0.92f, 1.02f, 0.94f, 0.76f);
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

    /** Saturation first, then per-channel scale (dim/cool or warm). */
    private static ColorMatrixColorFilter stateFilter(
            float sat, float r, float g, float b) {
        ColorMatrix s = new ColorMatrix();
        s.setSaturation(sat);
        ColorMatrix sc = new ColorMatrix();
        sc.setScale(r, g, b, 1f);
        sc.postConcat(s);
        return new ColorMatrixColorFilter(sc);
    }

    public void setState(int s) {
        if (state == s) return;
        int old = state;
        state = s;
        long now = System.currentTimeMillis();
        // Reaction moments, keyed to the service's true transitions:
        // →ON fires the nod/flare burst; →OFF from any live state
        // fires the settle. Nothing here guesses state — setState is
        // only ever called with FpnVpnService.uiState().
        if (s == STATE_ON) burstStartMs = now;
        if (s == STATE_OFF && old != STATE_OFF) settleStartMs = now;
        if (s != STATE_OFF) settleYpx = 0f;
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
        // The hero bitmap spans the same 1100-unit reference canvas.
        heroRect.set(cx - 550f * unit, cy - 550f * unit,
                cx + 550f * unit, cy + 550f * unit);
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
        // Breathing rate per state — SLOW everywhere, by Majid's
        // word («فقط آهسته تکون بخوره»): periods ≈ 11 s OFF,
        // ≈ 6 s CONNECTING, ≈ 8 s ON.
        phaseBob += dt * 1.5 * (state == STATE_OFF ? 0.38
                : state == STATE_CONNECTING ? 0.70 : 0.50);
        phaseSway += dt * 0.65 * speedMul;
        phaseRing += dt * 0.12 * speedMul;
        double t = (now - startMs) / 1000.0;

        // Reaction (→ON): a gentle glow swell over ~1.1 s.
        float flare = 1f;
        if (burstStartMs > 0) {
            double p = (now - burstStartMs) / 1100.0;
            if (p >= 1) burstStartMs = 0;
            else flare = 1f + 0.5f * (float) Math.sin(p * Math.PI);
        }
        // Settle (→OFF): the head sinks ~3 dp over ~700 ms and rests
        // there while OFF; the OFF color filter dims it.
        if (settleStartMs > 0) {
            double p = (now - settleStartMs) / 700.0;
            if (p >= 1) { settleStartMs = 0; settleYpx = 10f * unit; }
            else {
                double e = 1 - (1 - p) * (1 - p);
                settleYpx = (float) (e * 10f * unit);
            }
        }

        // Glow halo: steady soft breathing when ON, a clear pulse
        // while CONNECTING, near-still when OFF; flare multiplies.
        float pulse = state == STATE_ON ? 1f + 0.015f * (float) Math.sin(t * 0.7)
                : state == STATE_CONNECTING ? 1f + 0.05f * (float) Math.sin(t * 1.6)
                : 1f;
        haloPaint.setAlpha((int) (255 * Math.min(1f, flare)));
        canvas.save();
        canvas.scale(pulse * flare, pulse * flare, cx, cy);
        canvas.drawCircle(cx, cy, 500f * unit, haloPaint);
        canvas.restore();
        haloPaint.setAlpha(255);

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

        // Fox head: breathing + the burst nod / OFF settle.
        canvas.save();
        canvas.translate(0, (float) Math.sin(phaseBob) * 90f * unit * 0.35f
                + settleYpx);
        float breatheAmp = state == STATE_OFF ? 0.0075f
                : state == STATE_CONNECTING ? 0.010f : 0.008f;
        float breathe = 1f + breatheAmp * (float) Math.sin(phaseBob);
        // →ON reaction: one gentle nod — a small slow dip, ~1.1 s.
        float nodY = 0f, squashY = 1f, squashX = 1f;
        if (burstStartMs > 0) {
            double p = (now - burstStartMs) / 1100.0;
            if (p < 1) {
                float s = (float) Math.sin(p * Math.PI);
                nodY = s * 16f * unit;
                squashY = 1f - 0.015f * s;
                squashX = 1f + 0.008f * s;
            }
        }
        canvas.translate(0, nodY);
        canvas.scale(breathe * squashX, breathe * squashY, cx, cy);
        if (heroBitmap == null) {
            canvas.rotate((float) Math.sin(phaseSway) * 2.6f, cx, cy);
            canvas.skew((float) Math.sin(phaseSway * 0.9) * 0.02f, 0f);
        }

        // The 4.4.0 hero art, state-filtered, with a perspective
        // head-turn: Camera rotationY around the view center reads
        // as the fox turning its head — strongest while CONNECTING
        // (waking/looking), a whisper when ON, near-still OFF.
        // (Fallback: the facet fox below, unchanged.)
        if (heroBitmap != null) {
            heroPaint.setColorFilter(state == STATE_ON ? null
                    : state == STATE_CONNECTING ? filterConnecting : filterOff);
            float turn = state == STATE_CONNECTING
                    ? (float) Math.sin(t * 0.55) * 6f
                    : state == STATE_ON ? (float) Math.sin(t * 0.35) * 2f
                    : (float) Math.sin(t * 0.22) * 1f;
            heroCamera.save();
            heroCamera.rotateY(turn);
            heroCamera.getMatrix(camMatrix);
            heroCamera.restore();
            camMatrix.preTranslate(-cx, -cy);
            camMatrix.postTranslate(cx, cy);
            heroMatrix.setRectToRect(heroSrcF, heroRect, Matrix.ScaleToFit.FILL);
            heroMatrix.postConcat(camMatrix);
            canvas.drawBitmap(heroBitmap, heroMatrix, heroPaint);
        } else {
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
        } // end facet fallback

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
