package ru.ino.senseix;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Dialog pilih mode aktivasi SenseiX: Shizuku atau Wireless (InoAdb).
 * Dibangun lewat kode: tanpa XML, tanpa androidx/appcompat.
 */
public final class ActivationDialog {

    private ActivationDialog() {}

    public interface Callback {
        void onShizuku();
        void onWireless();
    }

    /**
     * @param shizukuStatus teks status Shizuku dari kode SenseiX, mis. "Shizuku aktif" / "Shizuku belum berjalan"
     * @param shizukuOk     true = tampil hijau, false = tampil oranye (tetap bisa dipilih)
     */
    public static Dialog show(final Activity a, String shizukuStatus, boolean shizukuOk, final Callback cb) {
        final Pal p = new Pal(a);
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(a, 20), dp(a, 20), dp(a, 20), dp(a, 12));
        root.setBackground(round(p.bg, dp(a, 24), 0, 0));

        root.addView(text(a, LanguageLoader.get("act_dialog_title", "Activate SenseiX"), 20, p.title, true));
        TextView sub = text(a, LanguageLoader.get("act_dialog_subtitle", "Choose an activation method"), 13, p.sub, false);
        sub.setPadding(0, dp(a, 2), 0, dp(a, 16));
        root.addView(sub);

        // --- Kartu 1: Shizuku
        root.addView(card(a, p, "Shizuku",
                LanguageLoader.get("act_shizuku_desc", "Use a running Shizuku. Fast, no pairing needed."),
                shizukuStatus, shizukuOk ? p.ok : p.warn, true,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { d.dismiss(); cb.onShizuku(); }
                }));

        // --- Kartu 2: Wireless (InoAdb)
        boolean supported = Build.VERSION.SDK_INT >= 30;
        String ws;
        int wc;
        if (!supported) { ws = LanguageLoader.get("act_status_unsupported", "Not supported (needs Android 11+)"); wc = p.bad; }
        else if (InoAdb.isPaired(a)) { ws = LanguageLoader.get("act_status_paired", "Already paired"); wc = p.ok; }
        else { ws = LanguageLoader.get("act_status_need_pair", "One-time pairing required"); wc = p.warn; }
        View w = card(a, p, "Wireless",
                LanguageLoader.get("act_wireless_desc", "No Shizuku needed. Uses Wireless debugging over Wi-Fi."),
                ws, wc, supported,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { d.dismiss(); cb.onWireless(); }
                });
        ((LinearLayout.LayoutParams) w.getLayoutParams()).topMargin = dp(a, 10);
        root.addView(w);

        // --- Batal
        TextView cancel = text(a, LanguageLoader.get("act_cancel", "Cancel"), 14, p.sub, true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, dp(a, 14), 0, dp(a, 6));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        root.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        d.setContentView(root);
        d.show();
        Window win = d.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.setLayout((int) (a.getResources().getDisplayMetrics().widthPixels * 0.9f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return d;
    }

    // ------------------------------------------------------------------ pembangun view

    private static View card(Activity a, Pal p, String title, String desc, String status,
                             int statusColor, boolean enabled, View.OnClickListener click) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
        GradientDrawable base = round(p.card, dp(a, 16), dp(a, 1), p.stroke);
        if (Build.VERSION.SDK_INT >= 21) {
            c.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple), base, null));
        } else {
            c.setBackground(base);
        }

        c.addView(text(a, title, 16, p.title, true));
        TextView d = text(a, desc, 13, p.sub, false);
        d.setPadding(0, dp(a, 4), 0, dp(a, 8));
        c.addView(d);
        c.addView(text(a, "\u25CF " + status, 12, statusColor, true));

        if (enabled) {
            c.setClickable(true);
            c.setOnClickListener(click);
        } else {
            c.setAlpha(0.45f);
        }
        c.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    private static TextView text(Activity a, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private static GradientDrawable round(int fill, int radius, int strokeW, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokeW > 0) g.setStroke(strokeW, strokeColor);
        return g;
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    /** Palet terang/gelap mengikuti tema sistem. */
    private static final class Pal {
        final int bg, card, stroke, title, sub, ripple, ok, warn, bad;
        Pal(Activity a) {
            boolean dark = (a.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            bg     = dark ? 0xFF1F2429 : 0xFFFFFFFF;
            card   = dark ? 0xFF2A3037 : 0xFFF5F6F8;
            stroke = dark ? 0xFF3A424B : 0xFFE0E3E8;
            title  = dark ? 0xFFF1F3F5 : 0xFF14181C;
            sub    = dark ? 0xFFA9B1BA : 0xFF5A636D;
            ripple = dark ? 0x33FFFFFF : 0x22000000;
            ok     = 0xFF2E9E5B;
            warn   = 0xFFE08A1E;
            bad    = 0xFFD64545;
        }
    }
}
