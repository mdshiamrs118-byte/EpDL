package com.epdl.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.IBinder;
import android.provider.MediaStore;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class DlService extends Service {
    static final AtomicInteger active = new AtomicInteger(); // running/queued jobs
    NotificationManager nm;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent in, int flags, int id) {
        final String u = in.getStringExtra("u"), n = in.getStringExtra("n"), f = in.getStringExtra("f");
        final String r = in.getStringExtra("r"), ua = in.getStringExtra("ua");
        final int th = in.getIntExtra("t", 4);
        final int nid = 100 + id;
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("d", "Downloads", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, note("EpDL", "Downloading…", 0, 0, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        new Thread(() -> {
            String msg;
            try {
                new Job(u, n, f, r, ua, th, nid).run();
                msg = "Saved to Download/EpDL/" + f;
            } catch (Exception e) {
                msg = "Failed: " + e;
            }
            nm.cancel(nid);
            nm.notify(1000 + nid, note(n, msg, 0, 0, false));
            if (active.decrementAndGet() <= 0) {
                stopForeground(true);
                stopSelf();
            }
        }).start();
        return START_NOT_STICKY;
    }

    Notification note(String t, String s, int max, int cur, boolean ongoing) {
        Notification.Builder b = new Notification.Builder(this, "d")
                .setContentTitle(t).setContentText(s).setOngoing(ongoing)
                .setSmallIcon(ongoing ? android.R.drawable.stat_sys_download : android.R.drawable.stat_sys_download_done);
        if (ongoing) b.setProgress(max, cur, max == 0);
        return b.build();
    }

    static String abs(String base, String rel) throws MalformedURLException {
        return new URL(new URL(base), rel).toString();
    }

    // some hosts prepend a fake image header to TS segments: skip to the real TS sync bytes
    static byte[] strip(byte[] b) {
        if (b.length > 0 && b[0] == 0x47) return b;
        int max = Math.min(b.length - 376, 65536);
        for (int i = 0; i < max; i++)
            if (b[i] == 0x47 && b[i + 188] == 0x47 && b[i + 376] == 0x47) return Arrays.copyOfRange(b, i, b.length);
        return b;
    }

    class Job {
        final String u0, n, f, ref, ua;
        final int th, nid;
        String init;
        SecretKeySpec key;
        byte[] iv0;
        long seq;
        final List<String> segs = new ArrayList<>();

        Job(String u, String n, String f, String ref, String ua, int th, int nid) {
            this.u0 = u; this.n = n; this.f = f; this.ref = ref; this.ua = ua; this.th = th; this.nid = nid;
        }

        void run() throws Exception {
            String u = u0;
            if (!u.toLowerCase().contains(".m3u8")) { direct(u); return; }

            String t = text(u);
            // master playlist -> best bandwidth
            if (t.contains("#EXT-X-STREAM-INF")) {
                String[] L = t.split("\\r?\\n");
                Pattern bp = Pattern.compile("BANDWIDTH=(\\d+)");
                long bw = -1;
                String best = null;
                for (int i = 0; i < L.length - 1; i++) {
                    Matcher m = bp.matcher(L[i]);
                    if (L[i].startsWith("#EXT-X-STREAM-INF") && m.find() && Long.parseLong(m.group(1)) > bw) {
                        bw = Long.parseLong(m.group(1));
                        best = L[i + 1].trim();
                    }
                }
                if (best != null) { u = abs(u, best); t = text(u); }
            }

            // AES-128 key
            Matcher km = Pattern.compile("#EXT-X-KEY:([^\\r\\n]+)").matcher(t);
            if (km.find() && !km.group(1).contains("METHOD=NONE")) {
                String a = km.group(1);
                if (!a.contains("METHOD=AES-128")) throw new IOException("unsupported encryption");
                Matcher um = Pattern.compile("URI=\"([^\"]+)\"").matcher(a);
                if (!um.find()) throw new IOException("no key uri");
                key = new SecretKeySpec(bytes(abs(u, um.group(1))), "AES");
                Matcher im = Pattern.compile("IV=0x([0-9a-fA-F]+)").matcher(a);
                if (im.find()) {
                    String h = String.format("%32s", im.group(1)).replace(' ', '0');
                    iv0 = new byte[16];
                    for (int i = 0; i < 16; i++) iv0[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
                }
            }
            Matcher sm = Pattern.compile("#EXT-X-MEDIA-SEQUENCE:(\\d+)").matcher(t);
            seq = sm.find() ? Long.parseLong(sm.group(1)) : 0;
            Matcher mm = Pattern.compile("#EXT-X-MAP:[^\\r\\n]*URI=\"([^\"]+)\"").matcher(t);
            init = mm.find() ? abs(u, mm.group(1)) : null;

            for (String l : t.split("\\r?\\n")) {
                l = l.trim();
                if (!l.isEmpty() && !l.startsWith("#")) segs.add(abs(u, l));
            }
            if (segs.isEmpty()) throw new IOException("no segments");

            Uri uri = create(init != null ? ".mp4" : ".ts", init != null ? "video/mp4" : "video/mp2t");
            ExecutorService ex = Executors.newFixedThreadPool(th);
            try {
                try (OutputStream out = new BufferedOutputStream(getContentResolver().openOutputStream(uri), 1 << 17)) {
                    if (init != null) out.write(bytes(init));
                    int tot = segs.size(), w = 0, win = th * 2;
                    List<Future<byte[]>> fs = new ArrayList<>();
                    for (int i = 0; i < tot; i++) {
                        final int k = i;
                        fs.add(ex.submit(() -> seg(k)));
                        while (fs.size() - w >= win || (i == tot - 1 && w < tot)) {
                            out.write(fs.get(w).get());
                            fs.set(w, null);
                            w++;
                            nm.notify(nid, note(n, w + "/" + tot, tot, w, true));
                        }
                    }
                }
                finish(uri);
            } catch (Exception e) {
                getContentResolver().delete(uri, null, null);
                throw e;
            } finally {
                ex.shutdownNow();
            }
        }

        void direct(String u) throws Exception {
            Uri uri = create(".mp4", "video/mp4");
            try {
                HttpURLConnection c = open(u);
                long len = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); OutputStream out = getContentResolver().openOutputStream(uri)) {
                    byte[] b = new byte[1 << 16];
                    int r;
                    long d = 0, last = 0;
                    while ((r = in.read(b)) > 0) {
                        out.write(b, 0, r);
                        d += r;
                        if ((d - last) > (1 << 20)) {
                            last = d;
                            nm.notify(nid, note(n, (d >> 20) + " MB", len > 0 ? (int) (len >> 10) : 0, (int) (d >> 10), true));
                        }
                    }
                }
                finish(uri);
            } catch (Exception e) {
                getContentResolver().delete(uri, null, null);
                throw e;
            }
        }

        byte[] seg(int i) throws Exception {
            byte[] b = bytes(segs.get(i));
            if (key != null) {
                byte[] iv = iv0;
                if (iv == null) {
                    iv = new byte[16];
                    long s = seq + i;
                    for (int k = 0; k < 8; k++) iv[15 - k] = (byte) (s >> (8 * k));
                }
                Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
                b = c.doFinal(b);
            }
            return init == null ? strip(b) : b;
        }

        HttpURLConnection open(String u) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            if (ua != null) c.setRequestProperty("User-Agent", ua);
            if (ref != null) {
                c.setRequestProperty("Referer", ref);
                try {
                    URL r = new URL(ref);
                    c.setRequestProperty("Origin", r.getProtocol() + "://" + r.getAuthority());
                } catch (Exception ignored) { }
            }
            return c;
        }

        byte[] bytes(String u) throws IOException {
            IOException last = null;
            for (int a = 0; a < 3; a++) {
                try {
                    HttpURLConnection c = open(u);
                    try (InputStream in = c.getInputStream()) {
                        ByteArrayOutputStream o = new ByteArrayOutputStream();
                        byte[] b = new byte[65536];
                        int r;
                        while ((r = in.read(b)) > 0) o.write(b, 0, r);
                        return o.toByteArray();
                    }
                } catch (IOException e) {
                    last = e;
                }
            }
            throw last;
        }

        String text(String u) throws IOException { return new String(bytes(u), "UTF-8"); }

        Uri create(String ext, String mime) throws IOException {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, n + ext);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/EpDL/" + f);
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new IOException("cannot create file");
            return u;
        }

        void finish(Uri u) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(u, v, null, null);
        }
    }
}
