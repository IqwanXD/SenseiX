package ru.ino.senseix;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Alur aktivasi mode Wireless memakai InoAdb:
 *   sudah pairing + diizinkan  -> langsung aktif
 *   belum                      -> notifikasi kode pairing -> pair -> connect -> aktif
 *
 * Pemakaian di Activity:
 *   onCreate/onClick : wireless = new WirelessActivator(this, listener); wireless.start();
 *   onResume         : wireless.recheck();
 *   onRequestPermissionsResult : wireless.onRequestPermissionsResult(...)
 *   onDestroy        : wireless.release();
 */
public final class WirelessActivator {

    public interface Listener {
        void onLog(String line);       // dipanggil di UI thread
        void onActivated();            // dipanggil di UI thread
        void onFailed(String reason);  // dipanggil di UI thread
    }

    private static final int REQ_NOTIF = 7101;

    private final Activity act;
    private final Listener cb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final InoService.Listener svc;
    private volatile boolean waiting;
    /** True selagi pengguna di layar pengaturan notifikasi; recheck() melanjutkan pairing begitu diizinkan. */
    private boolean awaitingNotifSettings;

    public WirelessActivator(Activity activity, Listener listener) {
        this.act = activity;
        this.cb = listener;
        this.svc = new InoService.Listener() {
            @Override public void onOutput(final String line) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        cb.onLog(line);
                        // InoService menulis baris ini setelah connect TLS berhasil.
                        if (waiting && "info: connected".equals(line)) done();
                    }
                });
            }
            @Override public void onResult(boolean ok) {}
        };
    }

    public boolean isWaiting() { return waiting; }

    public void start() {
        if (Build.VERSION.SDK_INT < 30) {
            cb.onFailed(LanguageLoader.get("act_needs_android11", "Wireless debugging requires Android 11 or newer"));
            return;
        }
        waiting = true;
        InoService.setListener(svc);
        if (InoAdb.isPaired(act)) {
            cb.onLog("info: memeriksa pairing yang sudah ada...");
            io.execute(new Runnable() {
                @Override public void run() {
                    final boolean ok = InoService.isAuthorized(act, 6000);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (!waiting) return;
                            if (ok) done();
                            else if (!wirelessEnabled()) {
                                // Pairing masih tersimpan, hanya Wireless debugging yang mati: tidak perlu pairing ulang.
                                // waiting tetap true, recheck() di onResume yang menyelesaikan setelah dinyalakan.
                                cb.onLog("hint: " + LanguageLoader.get("act_wireless_off_hint", "Wireless debugging is off. Turn it on, then come back to the app (no re-pairing needed)."));
                                openWirelessDebugging();
                            } else {
                                cb.onLog("info: pairing lama tidak berlaku, pairing baru...");
                                requestNotifThenPair();
                            }
                        }
                    });
                }
            });
        } else {
            requestNotifThenPair();
        }
    }

    /** Panggil dari onResume: pairing lewat notifikasi bisa selesai saat aplikasi di latar. */
    public void recheck() {
        if (!waiting) return;
        // Kembali dari pengaturan notifikasi: kalau sudah diizinkan, lanjut ke Opsi pengembang.
        if (awaitingNotifSettings && notificationsAllowed()) {
            awaitingNotifSettings = false;
            beginPairing();
            return;
        }
        io.execute(new Runnable() {
            @Override public void run() {
                final boolean ok = InoService.isAuthorized(act, 3000);
                ui.post(new Runnable() {
                    @Override public void run() { if (ok && waiting) done(); }
                });
            }
        });
    }

    public void cancel() {
        waiting = false;
        awaitingNotifSettings = false;
        InoService.stop(act);
    }

    public void release() {
        waiting = false;
        awaitingNotifSettings = false;
        InoService.setListener(null);
        io.shutdown();
    }

    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req != REQ_NOTIF) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && notificationsAllowed()) {
            beginPairing();
        } else {
            // Ditolak / tidak ada dialog: arahkan ke pengaturan notifikasi app, jangan langsung gagal.
            openNotificationSettings();
        }
    }

    // ------------------------------------------------------------------ internal

    private void done() {
        waiting = false;
        cb.onActivated();
    }

    /** Status toggle Wireless debugging di sistem. Kalau tidak terbaca, anggap nyala (perilaku lama). */
    private boolean wirelessEnabled() {
        try {
            return Settings.Global.getInt(act.getContentResolver(), "adb_wifi_enabled", 0) == 1;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Notifikasi benar-benar bisa tampil? Berlaku di semua targetSdk (app ini targetSdk 28, jadi
     * dialog requestPermissions(POST_NOTIFICATIONS) di Android 13+ tidak muncul), dan ikut
     * memeriksa channel pairing yang dimatikan.
     */
    private boolean notificationsAllowed() {
        try {
            if (Build.VERSION.SDK_INT < 24) return true;
            NotificationManager nm = (NotificationManager) act.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return true;
            if (!nm.areNotificationsEnabled()) return false;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = nm.getNotificationChannel("ino_adb"); // CHANNEL di InoService
                if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    private void requestNotifThenPair() {
        // Dialog izin hanya ada kalau app menargetkan SDK 33+. Selain itu langsung cek status notifikasi.
        if (Build.VERSION.SDK_INT >= 33
                && act.getApplicationInfo().targetSdkVersion >= 33
                && act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        if (!notificationsAllowed()) {
            openNotificationSettings();
            return;
        }
        beginPairing();
    }

    /** Buka pengaturan notifikasi SenseiX. Pairing dilanjutkan otomatis dari recheck() setelah diizinkan. */
    private void openNotificationSettings() {
        awaitingNotifSettings = true;
        cb.onLog("hint: " + LanguageLoader.get("act_notif_settings_hint",
                "Allow notifications for SenseiX, then come back to the app to continue pairing."));
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, act.getPackageName());
            act.startActivity(i);
            return;
        } catch (RuntimeException ignored) {}
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(android.net.Uri.parse("package:" + act.getPackageName()));
            act.startActivity(i);
        } catch (RuntimeException e) {
            awaitingNotifSettings = false;
            waiting = false;
            cb.onFailed(LanguageLoader.get("act_notif_permission", "Notification permission is required to enter the pairing code"));
        }
    }

    private void beginPairing() {
        InoService.startPairing(act);
        cb.onLog("info: pilih \"Pairkan perangkat dengan kode pairing\", lalu ketik kodenya di notifikasi.");
        openWirelessDebugging();
    }

    private void openWirelessDebugging() {
        try {
            act.startActivity(new Intent("android.settings.ADB_WIRELESS_SETTINGS"));
            return;
        } catch (RuntimeException ignored) {}
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            i.putExtra(":settings:fragment_args_key", "toggle_adb_wireless");
            Bundle args = new Bundle();
            args.putString(":settings:fragment_args_key", "toggle_adb_wireless");
            i.putExtra(":settings:show_fragment_args", args);
            act.startActivity(i);
            return;
        } catch (RuntimeException ignored) {}
        cb.onLog("info: buka Pengaturan > Opsi pengembang > Wireless debugging secara manual");
    }
}
