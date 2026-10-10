package ru.ino.senseix;

import android.Manifest;
import android.app.Activity;
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
            cb.onFailed("Wireless debugging butuh Android 11 atau lebih baru");
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
                            else {
                                cb.onLog("info: pairing lama tidak berlaku / Wireless debugging mati, pairing baru...");
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
        InoService.stop(act);
    }

    public void release() {
        waiting = false;
        InoService.setListener(null);
        io.shutdown();
    }

    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req != REQ_NOTIF) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            beginPairing();
        } else {
            waiting = false;
            cb.onFailed("Izin notifikasi diperlukan untuk memasukkan kode pairing");
        }
    }

    // ------------------------------------------------------------------ internal

    private void done() {
        waiting = false;
        cb.onActivated();
    }

    private void requestNotifThenPair() {
        if (Build.VERSION.SDK_INT >= 33
                && act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        beginPairing();
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
