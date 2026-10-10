package ru.ino.senseix;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
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
 * Gaya sama dengan tampilkanDialogKustom: font main.ttf, warna dari themeHelper,
 * root radius 28, kartu radius 20, divider, tombol teks di kanan bawah.
 * Dibangun lewat kode: tanpa XML, tanpa androidx/appcompat.
 */
public final class ActivationDialog {

    private ActivationDialog() {}

    public interface Callback {
        void onShizuku();
        void onWireless();
    }

    /**
     * @param th            themeHelper dari MainActivity (ganti tipe ThemeHelper jika nama kelasmu beda)
     * @param shizukuStatus teks status Shizuku, mis. "Shizuku aktif" / "Shizuku belum berjalan"
     * @param shizukuOk     true = hijau, false = oranye (tetap bisa dipilih)
     */
    public static Dialog show(final Activity a, final ThemeHelper th,
                              String shizukuStatus, boolean shizukuOk, final Callback cb) {
        final Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        final float dp = a.getResources().getDisplayMetrics().density;
        Typeface font;
        try {
            font = Typeface.createFromAsset(a.getAssets(), "fonts/main.ttf");
        } catch (Exception e) {
            font = Typeface.DEFAULT;
        }
        final Typeface inoFont = font;
        final Typeface inoBold = Typeface.create(inoFont, Typeface.BOLD);

        final int cOk   = 0xFF2E9E5B;
        final int cWarn = 0xFFE08A1E;
        final int cBad  = 0xFFD64545;

        // ROOT
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (24 * dp), (int) (24 * dp), (int) (24 * dp), (int) (18 * dp));
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(th.getColor("colorSurfaceContainer"));
        rootBg.setCornerRadius(28 * dp);
        root.setBackground(rootBg);

        // TITLE
        TextView title = new TextView(a);
        title.setText(LanguageLoader.get("act_dialog_title", "Activate SenseiX"));
        title.setTextSize(24);
        title.setTypeface(inoBold);
        title.setTextColor(th.getColor("colorOnSurface"));
        title.setPadding(0, 0, 0, (int) (8 * dp));
        root.addView(title);

        // SUBTITLE
        TextView subtitle = new TextView(a);
        subtitle.setText(LanguageLoader.get("act_dialog_subtitle", "Choose an activation method"));
        subtitle.setTextSize(13);
        subtitle.setTypeface(inoFont);
        subtitle.setTextColor(th.getColor("colorPrimary"));
        subtitle.setPadding(0, 0, 0, (int) (18 * dp));
        root.addView(subtitle);

        // KARTU 1: Shizuku
        View c1 = card(a, th, dp, inoFont, inoBold,
                "Shizuku",
                LanguageLoader.get("act_shizuku_desc", "Use a running Shizuku. Fast, no pairing needed."),
                shizukuStatus, shizukuOk ? cOk : cWarn, true,
                v -> { dialog.dismiss(); cb.onShizuku(); });
        root.addView(c1);

        // KARTU 2: Wireless (InoAdb)
        boolean supported = Build.VERSION.SDK_INT >= 30;
        String ws;
        int wc;
        if (!supported) {
            ws = LanguageLoader.get("act_status_unsupported", "Not supported (needs Android 11+)");
            wc = cBad;
        } else if (InoAdb.isPaired(a)) {
            ws = LanguageLoader.get("act_status_paired", "Already paired");
            wc = cOk;
        } else {
            ws = LanguageLoader.get("act_status_need_pair", "One-time pairing required");
            wc = cWarn;
        }
        View c2 = card(a, th, dp, inoFont, inoBold,
                "Wireless",
                LanguageLoader.get("act_wireless_desc", "No Shizuku needed. Uses Wireless debugging over Wi-Fi."),
                ws, wc, supported,
                v -> { dialog.dismiss(); cb.onWireless(); });
        ((LinearLayout.LayoutParams) c2.getLayoutParams()).topMargin = (int) (10 * dp);
        root.addView(c2);

        // SPACE
        View spacer = new View(a);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (18 * dp)));
        root.addView(spacer);

        // DIVIDER
        View divider = new View(a);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (1 * dp)));
        divider.setBackgroundColor(th.getColor("colorOutline"));
        root.addView(divider);

        // BUTTON ROW
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        row.setPadding(0, (int) (14 * dp), 0, 0);

        TextView btnCancel = new TextView(a);
        btnCancel.setText(LanguageLoader.get("act_cancel", "Cancel"));
        btnCancel.setTextSize(14);
        btnCancel.setTypeface(inoBold);
        btnCancel.setTextColor(th.getColor("colorPrimary"));
        btnCancel.setPadding((int) (16 * dp), (int) (12 * dp), (int) (16 * dp), (int) (12 * dp));
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        row.addView(btnCancel);
        root.addView(row);

        dialog.setContentView(root);

        Window w = dialog.getWindow();
        if (w != null) {
            int width = (int) (a.getResources().getDisplayMetrics().widthPixels * 0.88f);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.CENTER);
        }

        dialog.show();
        return dialog;
    }

    // ------------------------------------------------------------------ pembangun kartu

    private static View card(Activity a, ThemeHelper th, float dp, Typeface font, Typeface bold,
                             String title, String desc, String status, int statusColor,
                             boolean enabled, View.OnClickListener click) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding((int) (16 * dp), (int) (16 * dp), (int) (16 * dp), (int) (16 * dp));

        GradientDrawable base = new GradientDrawable();
        base.setCornerRadius(20 * dp);
        base.setColor(th.getColor("colorSurfaceContainerHigh"));

        if (Build.VERSION.SDK_INT >= 21) {
            int ripple = (th.getColor("colorPrimary") & 0x00FFFFFF) | 0x33000000;
            c.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), base, null));
        } else {
            c.setBackground(base);
        }

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(16);
        t.setTypeface(bold);
        t.setTextColor(th.getColor("colorOnSurface"));
        c.addView(t);

        TextView d = new TextView(a);
        d.setText(desc);
        d.setTextSize(14);
        d.setTypeface(font);
        d.setTextColor(th.getColor("colorOnSurfaceVariant"));
        d.setPadding(0, (int) (4 * dp), 0, (int) (10 * dp));
        c.addView(d);

        TextView s = new TextView(a);
        s.setText("● " + status);
        s.setTextSize(12);
        s.setTypeface(bold);
        s.setTextColor(statusColor);
        c.addView(s);

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
}
