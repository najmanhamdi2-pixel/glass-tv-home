package com.gathin.tvhome;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TimeZone;
final class PremierLeague {
    private static final String TAG = "TvHomeEpl";
    private static final String PREFS = "premier_league";
    private static final String KEY_DATA = "chelsea";
    private static final String KEY_AT = "fetched_at";
    private static final long REFRESH_MS = 3L * 60 * 60 * 1000;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static boolean running;
    private PremierLeague() {
    }
    static void syncIfDue(Context context, Runnable onUpdated) {
        final Context app = context.getApplicationContext();
        synchronized (PremierLeague.class) {
            if (running) return;
            SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long age = System.currentTimeMillis() - prefs.getLong(KEY_AT, 0);
            if (prefs.contains(KEY_DATA) && age >= 0 && age < REFRESH_MS) return;
            running = true;
        }
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(EplFixtures.url(System.currentTimeMillis())).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/json");
                if (conn.getResponseCode() != 200) {
                    Log.w(TAG, "fixtures feed answered " + conn.getResponseCode());
                    return;
                }
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buf = new char[4096];
                    int n;
                    while ((n = r.read(buf)) > 0) {
                        sb.append(buf, 0, n);
                        if (sb.length() > MAX_BYTES) {
                            Log.w(TAG, "fixtures feed too large; ignored");
                            return;
                        }
                    }
                }
                String json = sb.toString();
                if (!EplFixtures.looksValid(json)) {
                    Log.w(TAG, "fixtures feed not in the expected shape; ignored");
                    return;
                }
                long now = System.currentTimeMillis();
                String kept = EplFixtures.encode(EplFixtures.parse(json, now, EplFixtures.MAX_KEPT));
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_DATA, kept).putLong(KEY_AT, now).apply();
                if (onUpdated != null) new Handler(Looper.getMainLooper()).post(onUpdated);
            } catch (Exception e) {
                Log.w(TAG, "fixtures offline/failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
                synchronized (PremierLeague.class) {
                    running = false;
                }
            }
        }, "epl-sync").start();
    }
    static String getLines(Context context) {
        try {
            String data = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DATA, null);
            if (data == null) return "";
            long now = System.currentTimeMillis();
            List<EplFixtures.Match> next = EplFixtures.decode(data, now, EplFixtures.MAX_SHOWN);
            return EplFixtures.format(next, TimeZone.getDefault(), now);
        } catch (Exception e) {
            return "";
        }
    }
}
