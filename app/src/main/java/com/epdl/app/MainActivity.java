package com.epdl.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    static final Pattern EP = Pattern.compile("^(.*-episode-)(\\d{0,6})/?$");
    static final Pattern MEDIA = Pattern.compile("\\.(m3u8|mp4)(\\?|$)", Pattern.CASE_INSENSITIVE);
    static final int RED = 0xFFE53935, GREEN = 0xFF43A047, GREY = 0xFF424242;

    WebView web;
    FrameLayout root;
    LinearLayout col;
    TextView more, dl;
    GradientDrawable dlBg;
    SharedPreferences sp;
    String prefix;
    int ep = 1;
    final List<String[]> found = new ArrayList<>(); // {url, referer}
    View custom;
    WebChromeClient.CustomViewCallback cb;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("p", 0);
        prefix = sp.getString("prefix", null);
        ep = sp.getInt("ep", 1);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);

        root = new FrameLayout(this);
        setContentView(root);
        web = new WebView(this);
        root.addView(web, -1, -1);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (!r.isForMainFrame()) return false;
                if (!r.getUrl().toString().startsWith("http")) return true; // never leave the app
                if (!block()) return false;
                if (r.isRedirect()) { // allow same-host redirects (http->https, trailing slash)
                    String cur = v.getUrl();
                    String h = r.getUrl().getHost();
                    if (cur != null && h != null && h.equals(Uri.parse(cur).getHost())) return false;
                }
                toast("Blocked redirect");
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap f) {
                synchronized (found) { found.clear(); }
                dlBg.setColor(RED);
                Matcher m = EP.matcher(url);
                if (m.matches() && !m.group(2).isEmpty()) {
                    prefix = m.group(1);
                    ep = Integer.parseInt(m.group(2));
                    save();
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (MEDIA.matcher(u).find()) {
                    synchronized (found) {
                        for (String[] f : found) if (f[0].equals(u)) return null;
                        found.add(new String[]{u, r.getRequestHeaders().get("Referer")});
                    }
                    runOnUiThread(() -> dlBg.setColor(GREEN));
                }
                return null;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View v, CustomViewCallback c) {
                if (custom != null) { c.onCustomViewHidden(); return; }
                custom = v;
                cb = c;
                root.addView(v, -1, -1);
                col.setVisibility(View.GONE);
                more.setVisibility(View.GONE);
                // immersive fullscreen
                getWindow().getDecorView().setSystemUiVisibility(5894);
            }

            @Override
            public void onHideCustomView() { hideCustom(); }
        });

        // floating buttons
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(btn("◀", GREY, v -> step(-1)), lp());
        dl = btn("↓", RED, v -> download());
        dlBg = (GradientDrawable) dl.getBackground();
        col.addView(dl, lp());
        col.addView(btn("▶", GREY, v -> step(1)), lp());
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        cp.rightMargin = dp(6);
        root.addView(col, cp);

        more = btn("⋮", GREY, v -> menu());
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END | Gravity.TOP);
        mp.topMargin = dp(8);
        mp.rightMargin = dp(6);
        root.addView(more, mp);

        web.loadUrl(prefix != null ? prefix + ep + "/" : "https://aniwatch.lu/");
    }

    // ---------- UI helpers ----------
    int dp(int x) { return (int) (x * getResources().getDisplayMetrics().density); }

    LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(44), dp(44));
        p.bottomMargin = dp(10);
        return p;
    }

    TextView btn(String t, int color, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(20);
        v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        v.setAlpha(0.85f);
        v.setOnClickListener(l);
        return v;
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    boolean block() { return sp.getBoolean("block", true); }

    boolean par() { return sp.getBoolean("par", true); }

    void save() { sp.edit().putString("prefix", prefix).putInt("ep", ep).apply(); }

    // ---------- menu ----------
    void menu() {
        PopupMenu pm = new PopupMenu(this, more);
        pm.getMenu().add(0, 1, 0, "Set URL");
        pm.getMenu().add(0, 2, 1, "Block redirects").setCheckable(true).setChecked(block());
        pm.getMenu().add(0, 3, 2, "Parallel download").setCheckable(true).setChecked(par());
        pm.getMenu().add(0, 4, 3, "Reload");
        pm.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: setUrl(); break;
                case 2:
                    sp.edit().putBoolean("block", !block()).apply();
                    toast(block() ? "Redirects blocked" : "Redirects allowed");
                    break;
                case 3:
                    sp.edit().putBoolean("par", !par()).apply();
                    toast(par() ? "Parallel on: faster, several at once" : "Parallel off");
                    break;
                case 4: web.reload(); break;
            }
            return true;
        });
        pm.show();
    }

    void setUrl() {
        final EditText e = new EditText(this);
        e.setSingleLine();
        e.setText(prefix == null ? "https://aniwatch.lu/" : prefix + ep + "/");
        new AlertDialog.Builder(this).setTitle("Episode URL").setView(e)
                .setPositiveButton("Go", (d, w) -> open(e.getText().toString().trim()))
                .setNegativeButton("Cancel", null).show();
    }

    void open(String u) {
        if (u.isEmpty()) return;
        if (!u.startsWith("http")) u = "https://" + u;
        Matcher m = EP.matcher(u);
        if (m.matches()) { // "...-episode-1/" or "...-episode-/"
            prefix = m.group(1);
            ep = m.group(2).isEmpty() ? 1 : Integer.parseInt(m.group(2));
            u = prefix + ep + "/";
        } else {
            prefix = null;
        }
        save();
        web.loadUrl(u);
    }

    void step(int d) {
        if (prefix == null) { toast("URL has no episode number"); return; }
        if (ep + d < 1) return;
        ep += d;
        save();
        web.loadUrl(prefix + ep + "/");
    }

    // ---------- download ----------
    void download() {
        if (!par() && DlService.active.get() > 0) { toast("A download is already running"); return; }
        final List<String[]> c;
        synchronized (found) { c = new ArrayList<>(found); }
        if (c.isEmpty()) { toast("No video detected yet. Press play on the video first."); return; }
        if (c.size() == 1) { start(c.get(0)); return; }
        String[] items = new String[c.size()];
        for (int i = 0; i < items.length; i++) {
            Uri u = Uri.parse(c.get(i)[0]);
            items[i] = u.getHost() + u.getPath();
        }
        new AlertDialog.Builder(this).setTitle("Pick video")
                .setItems(items, (d, w) -> start(c.get(w))).show();
    }

    void start(String[] f) {
        String page = web.getUrl();
        String name = Uri.parse(page == null ? "" : page).getLastPathSegment();
        name = name == null ? "video" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        String folder = name.replaceAll("-episode-\\d*$", "");
        if (folder.isEmpty()) folder = name;
        DlService.active.incrementAndGet();
        Intent i = new Intent(this, DlService.class)
                .putExtra("u", f[0])
                .putExtra("r", f[1] != null ? f[1] : page)
                .putExtra("n", name)
                .putExtra("f", folder)
                .putExtra("t", par() ? 16 : 4)
                .putExtra("ua", web.getSettings().getUserAgentString());
        startForegroundService(i);
        toast("Downloading " + name);
    }

    // ---------- lifecycle ----------
    void hideCustom() {
        if (custom == null) return;
        root.removeView(custom);
        custom = null;
        if (cb != null) cb.onCustomViewHidden();
        cb = null;
        col.setVisibility(View.VISIBLE);
        more.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(0);
    }

    @Override
    public void onBackPressed() {
        if (custom != null) hideCustom();
        else if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
