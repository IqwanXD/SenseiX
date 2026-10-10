package ru.ino.senseix;

import android.content.Intent;
import android.net.VpnService;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DNS changer berbasis VpnService.
 *
 * Cara kerja:
 *  - Hanya IP DNS (dns1 & dns2) yang di-route ke TUN, jadi traffic lain tetap lewat jaringan normal.
 *  - Setiap query DNS (UDP/53, IPv4) yang masuk ke TUN dibaca, diteruskan ke server DNS asli
 *    lewat socket yang di-protect() (bypass VPN), lalu balasannya dibungkus lagi jadi paket IP/UDP
 *    dan ditulis balik ke TUN.
 *
 * Catatan: DNS lewat TCP/53, DoT, DoH dan IPv6 tidak ditangani (tidak di-route).
 */
public class MyVpnService extends VpnService {

	// ================= DNS =================

	/*private static String dns1 = "94.140.14.14";
	private static String dns2 = "94.140.15.15";*/

	private static String dns1 = "185.228.168.9";
	private static String dns2 = "185.228.169.9";

	private static final int DNS_TIMEOUT_MS = 3000;

	// ================= STATE =================

	private ParcelFileDescriptor vpn;
	private FileOutputStream tunOut;
	private Thread reader;
	private ExecutorService pool;
	private volatile boolean running = false;
	private final Object writeLock = new Object();

	// ================= START =================

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {

		if (intent != null && "STOP".equals(intent.getAction())) {
			stopVpn();
			return START_NOT_STICKY;
		}

		startVpn();

		return START_STICKY;
	}

	// ================= START VPN =================

	private synchronized void startVpn() {

		// tutup sesi lama (misal setelah ganti DNS)
		closeTunnel();

		try {
			Builder builder = new Builder();
			builder.setSession("IqwanoINO DNS")
				.addAddress("10.0.0.2", 24)
				.addDnsServer(dns1)
				.addDnsServer(dns2)
				.addRoute(dns1, 32);

			if (!dns2.equals(dns1)) {
				builder.addRoute(dns2, 32);
			}

			vpn = builder.establish();

			// null = izin VPN belum diberikan (panggil VpnService.prepare dulu)
			if (vpn == null) return;

			tunOut = new FileOutputStream(vpn.getFileDescriptor());
			pool = Executors.newFixedThreadPool(8);
			running = true;

			final FileInputStream in = new FileInputStream(vpn.getFileDescriptor());

			reader = new Thread(new Runnable() {
				@Override
				public void run() {
					readLoop(in);
				}
			}, "DnsVpnReader");
			reader.start();

		} catch (Exception e) {
			e.printStackTrace();
			closeTunnel();
		}
	}

	// ================= READ LOOP =================

	private void readLoop(FileInputStream in) {
		byte[] buf = new byte[32767];
		try {
			while (running) {
				int len = in.read(buf);
				if (len < 0) break;
				if (len == 0) continue;
				handlePacket(buf, len);
			}
		} catch (Exception ignored) {
			// TUN ditutup -> keluar
		}
	}

	// ================= HANDLE PACKET =================

	private void handlePacket(byte[] buf, int len) {

		// hanya IPv4
		if (len < 20 || ((buf[0] >> 4) & 0x0F) != 4) return;

		int ihl = (buf[0] & 0x0F) * 4;
		if (ihl < 20 || len < ihl + 8) return;

		// hanya UDP
		if ((buf[9] & 0xFF) != 17) return;

		// abaikan paket fragment
		if ((u16(buf, 6) & 0x3FFF) != 0) return;

		final int srcPort = u16(buf, ihl);
		int dstPort = u16(buf, ihl + 2);
		if (dstPort != 53) return;

		int udpLen = u16(buf, ihl + 4);
		int payloadLen = Math.min(udpLen - 8, len - ihl - 8);
		if (payloadLen <= 0) return;

		final byte[] srcIp = Arrays.copyOfRange(buf, 12, 16);
		final byte[] dstIp = Arrays.copyOfRange(buf, 16, 20);
		final byte[] query = Arrays.copyOfRange(buf, ihl + 8, ihl + 8 + payloadLen);

		ExecutorService p = pool;
		if (p == null || p.isShutdown()) return;

		try {
			p.execute(new Runnable() {
				@Override
				public void run() {
					forwardQuery(srcIp, dstIp, srcPort, query);
				}
			});
		} catch (Exception ignored) {
		}
	}

	// ================= FORWARD QUERY =================

	private void forwardQuery(byte[] srcIp, byte[] dstIp, int srcPort, byte[] query) {

		String first = ipToString(dstIp);
		String second = first.equals(dns1) ? dns2 : dns1;

		byte[] answer = ask(first, query);
		if (answer == null && !second.equals(first)) {
			answer = ask(second, query);
		}

		if (answer == null) return;

		writeResponse(srcIp, dstIp, srcPort, answer);
	}

	private byte[] ask(String server, byte[] query) {
		DatagramSocket socket = null;
		try {
			socket = new DatagramSocket();

			// penting: socket ini harus keluar lewat jaringan asli, bukan TUN
			if (!protect(socket)) return null;

			socket.setSoTimeout(DNS_TIMEOUT_MS);

			InetAddress addr = InetAddress.getByName(server);
			socket.send(new DatagramPacket(query, query.length, addr, 53));

			byte[] rbuf = new byte[4096];
			DatagramPacket resp = new DatagramPacket(rbuf, rbuf.length);
			socket.receive(resp);

			return Arrays.copyOf(rbuf, resp.getLength());

		} catch (SocketTimeoutException e) {
			return null;
		} catch (Exception e) {
			return null;
		} finally {
			if (socket != null) {
				try {
					socket.close();
				} catch (Exception ignored) {
				}
			}
		}
	}

	// ================= BUILD & WRITE RESPONSE =================

	private void writeResponse(byte[] clientIp, byte[] dnsIp, int clientPort, byte[] payload) {

		int n = payload.length;
		int total = 28 + n;
		byte[] p = new byte[total];

		// ---- IPv4 header ----
		p[0] = 0x45;                    // version 4, IHL 5
		p[1] = 0;                       // TOS
		p[2] = (byte) (total >> 8);
		p[3] = (byte) total;
		p[4] = 0;                       // identification
		p[5] = 0;
		p[6] = 0x40;                    // DF
		p[7] = 0;
		p[8] = 64;                      // TTL
		p[9] = 17;                      // UDP
		System.arraycopy(dnsIp, 0, p, 12, 4);     // src = server DNS
		System.arraycopy(clientIp, 0, p, 16, 4);  // dst = client

		int ck = checksum(p, 0, 20);
		p[10] = (byte) (ck >> 8);
		p[11] = (byte) ck;

		// ---- UDP header ----
		p[20] = 0;
		p[21] = 53;                     // src port
		p[22] = (byte) (clientPort >> 8);
		p[23] = (byte) clientPort;      // dst port
		int ul = 8 + n;
		p[24] = (byte) (ul >> 8);
		p[25] = (byte) ul;
		p[26] = 0;                      // UDP checksum 0 = tidak dipakai (valid di IPv4)
		p[27] = 0;

		System.arraycopy(payload, 0, p, 28, n);

		try {
			synchronized (writeLock) {
				if (tunOut != null) tunOut.write(p);
			}
		} catch (Exception ignored) {
		}
	}

	// ================= HELPERS =================

	private static int u16(byte[] b, int off) {
		return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
	}

	private static int checksum(byte[] b, int off, int len) {
		long sum = 0;
		for (int i = off; i < off + len; i += 2) {
			sum += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
		}
		while ((sum >> 16) != 0) {
			sum = (sum & 0xFFFF) + (sum >> 16);
		}
		return (int) (~sum & 0xFFFF);
	}

	private static String ipToString(byte[] ip) {
		return (ip[0] & 0xFF) + "." + (ip[1] & 0xFF) + "."
			+ (ip[2] & 0xFF) + "." + (ip[3] & 0xFF);
	}

	// ================= CLOSE TUNNEL =================

	private synchronized void closeTunnel() {

		running = false;

		if (reader != null) {
			reader.interrupt();
			reader = null;
		}

		if (pool != null) {
			pool.shutdownNow();
			pool = null;
		}

		try {
			if (vpn != null) {
				vpn.close();
			}
		} catch (Exception ignored) {
		}
		vpn = null;

		try {
			if (tunOut != null) {
				tunOut.close();
			}
		} catch (Exception ignored) {
		}
		tunOut = null;
	}

	// ================= STOP VPN =================

	private void stopVpn() {
		closeTunnel();
		stopForeground(true);
		stopSelf();
	}

	// ================= CHANGE DNS =================

	/**
	 * Ganti DNS. Agar langsung berlaku saat VPN sedang jalan,
	 * panggil startService lagi setelah setDns().
	 */
	public static void setDns(String primary, String secondary) {
		dns1 = primary;
		dns2 = secondary;
	}

	// ================= GET DNS =================

	public static String getPrimaryDns() {
		return dns1;
	}

	public static String getSecondaryDns() {
		return dns2;
	}

	// ================= REVOKE =================

	@Override
	public void onRevoke() {
		// user mematikan VPN dari sistem
		stopVpn();
		super.onRevoke();
	}

	// ================= DESTROY =================

	@Override
	public void onDestroy() {
		closeTunnel();
		super.onDestroy();
	}

	// ================= BIND =================

	@Override
	public IBinder onBind(Intent intent) {
		return super.onBind(intent);
	}
}
