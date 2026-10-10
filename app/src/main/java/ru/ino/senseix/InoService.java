package ru.ino.senseix;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * InoService: antarmuka pairing/connect/shell.
 *  - Service: cari layanan pairing lewat mDNS, tampilkan notifikasi dengan kolom reply kode 6 digit.
 *  - Static API (panggil dari thread background): pair(), connect(), shell(), discover().
 * Semua hasil berupa String bergaya Shizuku ("info: ...").
 */
public class InoService extends Service {

    private static final String P = InoService.class.getName();
    public static final String ACTION_START = P + ".START";
    public static final String ACTION_REPLY = P + ".REPLY";
    public static final String ACTION_STOP = P + ".STOP";
    public static final String TYPE_PAIRING = "_adb-tls-pairing._tcp.";
    public static final String TYPE_CONNECT = "_adb-tls-connect._tcp.";

    private static final String CHANNEL = "ino_adb";
    private static final String KEY_CODE = "code";
    private static final int NOTIF_ID = 7001;

    // ------------------------------------------------------------------ hasil & listener

    public static final class Result {
        public final boolean ok;
        public final String output;
        Result(boolean ok, String output) { this.ok = ok; this.output = output; }
        @Override public String toString() { return output; }
    }

    public interface Listener { void onOutput(String line); void onResult(boolean ok); }

    private static volatile Listener sListener;
    public static void setListener(Listener l) { sListener = l; }

    /** Baris output yang muncul saat tidak ada UI yang mendengarkan (aplikasi ditutup / di latar). */
    private static final StringBuilder sPending = new StringBuilder();

    /** Ambil dan kosongkan baris yang tertinggal. Panggil saat UI dibuat. */
    public static String drainPending() {
        synchronized (sPending) {
            String s = sPending.toString();
            sPending.setLength(0);
            return s;
        }
    }

    private static final class Log implements InoAdb.Out {
        final StringBuilder sb = new StringBuilder();
        public void line(String s) {
            sb.append(s).append('\n');
            Listener l = sListener;
            if (l != null) {
                l.onOutput(s);
            } else {
                synchronized (sPending) {
                    if (sPending.length() < 20000) sPending.append(s).append('\n');
                }
            }
        }
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    // ------------------------------------------------------------------ API statis (sinkron)

    /** Port pairing terakhir. Port ini tidak pernah valid untuk connect. */
    private static volatile int sLastPairPort = -1;

    public static Result pair(Context ctx, String host, int port, String code) {
        sLastPairPort = port;
        Log log = new Log();
        log.line("Starting with wireless adb in port " + port + "...");
        log.line("");
        log.line("info: pairing begin");
        log.line("info: connecting to " + host + ":" + port);
        boolean ok = false;
        try {
            InoAdb.pair(ctx, host, port, code, log);
            log.line("info: pairing succeeded, device name is " + InoAdb.getDeviceName());
            ok = true;
        } catch (Throwable t) {
            log.line("error: " + t);
        }
        Listener l = sListener;
        if (l != null) l.onResult(ok);
        return new Result(ok, log.sb.toString());
    }

    /** host == null: pakai endpoint tersimpan, jika gagal cari ulang lewat mDNS. */
    public static Result connect(Context ctx, String host, int port) {
        Log log = new Log();
        boolean ok = false;
        try {
            InoAdb.Client c = open(ctx, host, port, log);
            c.close();
            ok = true;
        } catch (Throwable t) {
            log.line("error: " + t);
        }
        Listener l = sListener;
        if (l != null) l.onResult(ok);
        return new Result(ok, log.sb.toString());
    }

    public static Result shell(Context ctx, String cmd) {
        Log log = new Log();
        boolean ok = false;
        try {
            InoAdb.Client c = open(ctx, null, 0, log);
            try {
                String r = c.shell(cmd);
                log.line(r.endsWith("\n") ? r.substring(0, r.length() - 1) : r);
                ok = true;
            } finally { c.close(); }
        } catch (Throwable t) {
            log.line("error: " + t);
        }
        return new Result(ok, log.sb.toString());
    }

    /** Aktifkan mode TCP (adb tcpip) lewat wireless debugging, lalu simpan port-nya. */
    public static Result tcpip(Context ctx, int tcpPort) {
        Log log = new Log();
        boolean ok = false;
        try {
            InoAdb.Client c = open(ctx, null, 0, log);
            try {
                log.line("info: " + c.exec("tcpip:" + tcpPort).trim());
                InoAdb.saveTcpPort(ctx, tcpPort);
                ok = true;
            } finally { c.close(); }
        } catch (Throwable t) {
            log.line("error: " + t);
        }
        return new Result(ok, log.sb.toString());
    }

    private static InoAdb.Client open(Context ctx, String host, int port, InoAdb.Out log) throws Exception {
        return open(ctx, host, port, log, 20000);
    }

    /** True kalau kunci kita sudah diterima adbd (connect TLS berhasil). Senyap: tidak menulis ke UI. */
    public static boolean isAuthorized(Context ctx, long mdnsTimeoutMs) {
        try {
            InoAdb.Client c = open(ctx, null, 0, new InoAdb.Out() { public void line(String s) {} }, mdnsTimeoutMs);
            c.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static InoAdb.Client open(Context ctx, String host, int port, InoAdb.Out log, long mdnsTimeoutMs) throws Exception {
        if (host == null) { host = InoAdb.savedHost(ctx); port = InoAdb.savedPort(ctx); }
        if (port == sLastPairPort) {
            log.line("info: port " + port + " adalah port pairing, diabaikan untuk connect");
            host = null;
        }
        if (host != null && port > 0) {
            try {
                log.line("info: connecting to " + host + ":" + port);
                InoAdb.Client c = InoAdb.Client.connect(ctx, host, port, log);
                InoAdb.saveEndpoint(ctx, host, port);
                log.line("info: connected");
                return c;
            } catch (Exception e) {
                log.line("info: endpoint lama gagal (" + e.getMessage() + "), cari lewat mDNS...");
            }
        }
        // Sumber 1: property sistem port TLS adbd (kalau bisa dibaca)
        int tp = tlsPortFromProp();
        if (tp > 0 && tp != sLastPairPort && probe("127.0.0.1", tp)) {
            try {
                log.line("info: connecting to 127.0.0.1:" + tp + " (service.adb.tls.port)");
                InoAdb.Client c = InoAdb.Client.connect(ctx, "127.0.0.1", tp, log);
                InoAdb.saveEndpoint(ctx, "127.0.0.1", tp);
                log.line("info: connected");
                return c;
            } catch (Exception e) {
                log.line("info: port dari property gagal (" + e.getMessage() + "), lanjut mDNS...");
            }
        }
        // Sumber 2: mDNS. Entri bisa basi (cache), jadi tiap kandidat diuji dulu dan yang ditolak dilewati.
        String[] f = findConnectEndpoint(ctx, log, mdnsTimeoutMs);
        if (f == null) throw new java.io.IOException("layanan wireless debugging tidak ditemukan (aktifkan dan sambung ke Wi-Fi)");
        log.line("info: mDNS found " + f[0] + ":" + f[1]);
        int p = Integer.parseInt(f[1]);
        InoAdb.Client c = InoAdb.Client.connect(ctx, f[0], p, log);
        InoAdb.saveEndpoint(ctx, f[0], p);
        log.line("info: connected");
        return c;
    }

    /** Buka klien ADB (endpoint tersimpan, property, atau mDNS). Pemanggil WAJIB menutup. Panggil dari thread background. */
    public static InoAdb.Client openClient(Context ctx, InoAdb.Out log) throws Exception {
        return open(ctx, null, 0, log);
    }

    /** Port TLS adbd saat ini dari property sistem, atau 0 kalau tidak terbaca. */
    private static int tlsPortFromProp() {
        try {
            Process p = new ProcessBuilder("getprop", "service.adb.tls.port").redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String l = r.readLine();
            r.close();
            p.waitFor();
            return l == null ? 0 : Integer.parseInt(l.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** True kalau ada yang mendengarkan di host:port (TCP saja, tanpa TLS). */
    private static boolean probe(String host, int port) {
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress(host, port), 2000);
            return true;
        } catch (java.io.IOException e) {
            return false;
        } finally {
            try { s.close(); } catch (java.io.IOException ignored) {}
        }
    }

    /** Cari endpoint connect yang benar-benar menerima koneksi; entri mDNS basi (ECONNREFUSED) dilewati. */
    private static String[] findConnectEndpoint(Context ctx, InoAdb.Out log, long timeoutMs) {
        final java.util.concurrent.LinkedBlockingQueue<String[]> q = new java.util.concurrent.LinkedBlockingQueue<String[]>();
        Discovery d = new Discovery(ctx, true, new Discovery.Callback() {
            public void onFound(String host, int port) { q.offer(new String[]{host, String.valueOf(port)}); }
            public void onLost(int port) {}
        });
        java.util.HashMap<String, Long> lastTry = new java.util.HashMap<String, Long>();
        try {
            d.start(TYPE_CONNECT);
            long end = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < end) {
                String[] c = q.poll(500, TimeUnit.MILLISECONDS);
                if (c == null) continue;
                String key = c[0] + ":" + c[1];
                Long t = lastTry.get(key);
                long now = System.currentTimeMillis();
                if (t != null && now - t < 3000) continue;
                lastTry.put(key, now);
                if (probe(c[0], Integer.parseInt(c[1]))) return c;
                log.line("info: " + key + " tidak aktif (entri mDNS basi), menunggu yang baru...");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
        } finally {
            d.stop();
        }
        return null;
    }

    /** Cari layanan lokal lewat mDNS. Return {host, port} atau null. */
    public static String[] discover(Context ctx, String type, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String[]> ref = new AtomicReference<String[]>();
        Discovery d = new Discovery(ctx, true, new Discovery.Callback() {
            public void onFound(String host, int port) {
                ref.compareAndSet(null, new String[]{host, String.valueOf(port)});
                latch.countDown();
            }
            public void onLost(int port) {}
        });
        try {
            d.start(type);
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        } finally {
            d.stop();
        }
        return ref.get();
    }

    // ------------------------------------------------------------------ mDNS

    static final class Discovery implements NsdManager.DiscoveryListener {
        interface Callback { void onFound(String host, int port); void onLost(int port); }

        private final NsdManager nsd;
        private final Callback cb;
        private final boolean localOnly;
        private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<NsdServiceInfo>();
        private final java.util.HashMap<String, Integer> ports = new java.util.HashMap<String, Integer>();
        private boolean resolving;
        private volatile boolean stopped;

        Discovery(Context c, boolean localOnly, Callback cb) {
            this.nsd = (NsdManager) c.getApplicationContext().getSystemService(Context.NSD_SERVICE);
            this.localOnly = localOnly;
            this.cb = cb;
        }

        void start(String type) { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, this); }

        void stop() {
            stopped = true;
            try { nsd.stopServiceDiscovery(this); } catch (Throwable ignored) {}
        }

        private void next() {
            final NsdServiceInfo s;
            synchronized (this) {
                if (resolving || stopped || queue.isEmpty()) return;
                resolving = true;
                s = queue.poll();
            }
            try {
                nsd.resolveService(s, new NsdManager.ResolveListener() {
                    public void onResolveFailed(NsdServiceInfo i, int code) { done(); }
                    public void onServiceResolved(NsdServiceInfo i) {
                        try { handle(i); } finally { done(); }
                    }
                });
            } catch (Throwable t) { done(); }
        }

        private void done() {
            synchronized (this) { resolving = false; }
            next();
        }

        private void handle(NsdServiceInfo i) {
            InetAddress a = i.getHost();
            if (a == null || stopped) return;
            if (localOnly) {
                try { if (NetworkInterface.getByInetAddress(a) == null) return; }
                catch (Exception e) { return; }
            }
            String host = a instanceof Inet4Address ? a.getHostAddress() : "127.0.0.1";
            synchronized (ports) { ports.put(i.getServiceName(), i.getPort()); }
            cb.onFound(host, i.getPort());
        }

        public void onServiceFound(NsdServiceInfo s) { synchronized (this) { queue.add(s); } next(); }
        public void onServiceLost(NsdServiceInfo s) {
            Integer p;
            synchronized (ports) { p = ports.remove(s.getServiceName()); }
            if (p != null && !stopped) cb.onLost(p);
        }
        public void onDiscoveryStarted(String t) {}
        public void onDiscoveryStopped(String t) {}
        public void onStartDiscoveryFailed(String t, int e) {}
        public void onStopDiscoveryFailed(String t, int e) {}
    }

    // ------------------------------------------------------------------ Service + notifikasi reply

    private Discovery discovery;
    private volatile String pHost;
    private volatile int pPort;

    /** Mulai mencari layanan pairing dan tampilkan notifikasi reply. */
    public static void startPairing(Context ctx) {
        Intent i = new Intent(ctx, InoService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, InoService.class).setAction(ACTION_STOP));
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        String a = i == null ? null : i.getAction();
        if (ACTION_STOP.equals(a)) {
            stopDiscovery();
            stopForeground(true);
            stopSelf();
        } else if (ACTION_REPLY.equals(a)) {
            handleReply(i);
        } else {
            ensureChannel();
            startFg(build("Mencari layanan pairing (Wi-Fi + Wireless debugging harus aktif)...", false, true));
            startDiscovery();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() { stopDiscovery(); super.onDestroy(); }

    private void startDiscovery() {
        stopDiscovery();
        discovery = new Discovery(this, true, new Discovery.Callback() {
            public void onFound(String host, int port) {
                pHost = host;
                pPort = port;
                new Log().line("info: layanan pairing ditemukan " + host + ":" + port);
                notifyNow(build("Ketik kode 6 digit dari Wireless debugging.", true, true));
            }
            public void onLost(int port) {
                if (pPort == port) {
                    pHost = null;
                    new Log().line("info: layanan pairing hilang (dialog kode pairing ditutup)");
                    notifyNow(build("Dialog kode pairing tertutup. Buka lagi \"Pairkan perangkat dengan kode pairing\".", false, true));
                }
            }
        });
        try { discovery.start(TYPE_PAIRING); }
        catch (Throwable t) { notifyNow(build("mDNS gagal: " + t.getMessage(), false, false)); }
    }

    private void stopDiscovery() {
        if (discovery != null) { discovery.stop(); discovery = null; }
    }

    private void handleReply(Intent i) {
        Bundle r = RemoteInput.getResultsFromIntent(i);
        CharSequence cs = r == null ? null : r.getCharSequence(KEY_CODE);
        final String code = cs == null ? "" : cs.toString().replaceAll("\\s", "");
        if (pHost == null) {
            notifyNow(build("Layanan pairing belum ditemukan. Buka Wireless debugging > Pairing code.", false, true));
            return;
        }
        if (!code.matches("\\d{6}")) {
            notifyNow(build("Kode harus 6 digit. Coba lagi.", true, true));
            return;
        }
        // Jangan pindah ke aplikasi dulu: meninggalkan Pengaturan menutup dialog kode pairing (port langsung mati).
        notifyNow(build("Memasangkan...", false, true));
        final String host = pHost;
        final int port = pPort;
        final Context ctx = getApplicationContext();
        EXEC.execute(new Runnable() {
            public void run() {
                Result res = pair(ctx, host, port, code);
                if (!res.ok) {
                    String last = lastLine(res.output);
                    if (last.contains("ECONNREFUSED") || last.contains("failed to connect")) {
                        // Port basi: dialog kode pairing sudah ditutup / port berganti. Tunggu layanan baru.
                        pHost = null;
                        notifyNow(build("Port pairing tidak aktif (dialog kode pairing tertutup). "
                                + "Buka lagi \"Pairkan perangkat dengan kode pairing\", notifikasi ini akan berubah.", false, true));
                    } else {
                        notifyNow(build("Gagal: " + last + " - masukkan kode lagi.", true, true));
                    }
                    return;
                }
                notifyNow(build("Pairing berhasil, menyambung...", false, true));
                Result c = connect(ctx, null, 0);
                stopDiscovery();
                notifyNow(build(c.ok ? "Tersambung sebagai " + InoAdb.getDeviceName()
                        : "Pairing OK, connect gagal: " + lastLine(c.output), false, false));
                stopForeground(false);
                stopSelf();
                bringAppToFront(); // pairing + connect selesai: sekarang aman pindah ke aplikasi
            }
        });
    }

    private static String lastLine(String s) {
        String[] p = s.trim().split("\n");
        return p.length == 0 ? "" : p[p.length - 1];
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Wireless ADB pairing", NotificationManager.IMPORTANCE_HIGH));
    }

    private Notification build(String text, boolean withReply, boolean ongoing) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(withReply ? "Layanan pairing ditemukan" : "InoAdb")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(ongoing)
                // Saat kolom kode muncul, notifikasi yang SAMA diperbarui dan muncul sebagai heads-up lagi.
                .setOnlyAlertOnce(!withReply);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);

        int mut = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        b.setContentIntent(PendingIntent.getActivity(this, 3, frontIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        if (withReply) {
            // Hanya satu aksi (kolom input), seperti Shizuku, supaya One UI menampilkan kolomnya langsung.
            RemoteInput ri = new RemoteInput.Builder(KEY_CODE).setLabel("Kode pairing").build();
            PendingIntent pi = PendingIntent.getService(this, 1,
                    new Intent(this, InoService.class).setAction(ACTION_REPLY),
                    PendingIntent.FLAG_UPDATE_CURRENT | mut);
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_edit), "Masukkan kode pairing", pi)
                    .addRemoteInput(ri).build());
        } else if (ongoing) {
            PendingIntent stop = PendingIntent.getService(this, 2,
                    new Intent(this, InoService.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Berhenti mencari", stop).build());
        }
        return b.build();
    }

    private Intent frontIntent() {
        return new Intent(this, MainActivity.class).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    }

    /** Pindah kembali ke aplikasi setelah kode dikirim dari notifikasi. */
    private void bringAppToFront() {
        try {
            startActivity(frontIntent());
        } catch (Throwable ignored) {
            // Kalau sistem menolak, hasil tetap masuk ke output saat aplikasi dibuka (lihat drainPending).
        }
    }

    private void startFg(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                return;
            } catch (RuntimeException e) {
                // Manifest tidak mendeklarasikan android:foregroundServiceType="connectedDevice":
                // jalan tanpa tipe supaya notifikasi tetap muncul.
            }
        }
        startForeground(NOTIF_ID, n);
    }

    private void notifyNow(Notification n) {
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_ID, n);
    }
}
