package com.gathin.tvhome;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
final class EplFixtures {
    static final String URL_BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard";
    static final String TEAM = "Chelsea";
    static final int DAYS_AHEAD = 30;
    static final int MAX_KEPT = 8;
    static final int MAX_SHOWN = 4;
    private static final long LIVE_WINDOW_MS = 3L * 60 * 60 * 1000;
    private static final int MAX_EVENTS = 200;
    private static final String[] DAYS = {"Aha", "Isn", "Sel", "Rab", "Kha", "Jum", "Sab"};
    private static final String[] MONTHS = {"Jan", "Feb", "Mac", "Apr", "Mei", "Jun", "Jul", "Ogo", "Sep", "Okt", "Nov", "Dis"};
    static final class Match {
        final long kickoffUtc;
        final String home;
        final String away;
        final String state;
        Match(long kickoffUtc, String home, String away, String state) {
            this.kickoffUtc = kickoffUtc;
            this.home = home;
            this.away = away;
            this.state = state;
        }
    }
    private EplFixtures() {
    }
    static String url(long nowMillis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String from = f.format(new Date(nowMillis - 24L * 60 * 60 * 1000));
        String to = f.format(new Date(nowMillis + DAYS_AHEAD * 24L * 60 * 60 * 1000));
        return URL_BASE + "?dates=" + from + "-" + to + "&limit=300";
    }
    static boolean looksValid(String json) {
        try {
            return new JSONObject(json).optJSONArray("events") != null;
        } catch (Exception e) {
            return false;
        }
    }
    static List<Match> parse(String json, long nowMillis, int max) {
        List<Match> out = new ArrayList<>();
        try {
            JSONArray events = new JSONObject(json).optJSONArray("events");
            if (events == null) return out;
            int n = Math.min(events.length(), MAX_EVENTS);
            for (int i = 0; i < n; i++) {
                try {
                    Match m = one(events.getJSONObject(i));
                    if (m == null || "post".equals(m.state) || !involves(m)) continue;
                    if (m.kickoffUtc < nowMillis - LIVE_WINDOW_MS) continue;
                    out.add(m);
                } catch (Exception skip) {
                }
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        Collections.sort(out, new Comparator<Match>() {
            @Override
            public int compare(Match a, Match b) {
                return Long.compare(a.kickoffUtc, b.kickoffUtc);
            }
        });
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }
    private static boolean involves(Match m) {
        return m.home.equalsIgnoreCase(TEAM) || m.away.equalsIgnoreCase(TEAM);
    }
    static String encode(List<Match> matches) {
        StringBuilder sb = new StringBuilder();
        for (Match m : matches) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(m.kickoffUtc).append('|').append(m.home).append('|').append(m.away).append('|').append(m.state);
        }
        return sb.toString();
    }
    static List<Match> decode(String data, long nowMillis, int max) {
        List<Match> out = new ArrayList<>();
        if (data == null) return out;
        for (String line : data.split("\n")) {
            try {
                String[] f = line.split("\\|", -1);
                if (f.length != 4) continue;
                long t = Long.parseLong(f[0]);
                if ("post".equals(f[3]) || t < nowMillis - LIVE_WINDOW_MS) continue;
                out.add(new Match(t, f[1], f[2], f[3]));
            } catch (Exception skip) {
            }
        }
        Collections.sort(out, new Comparator<Match>() {
            @Override
            public int compare(Match a, Match b) {
                return Long.compare(a.kickoffUtc, b.kickoffUtc);
            }
        });
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }
    private static Match one(JSONObject ev) throws Exception {
        long kickoff = parseDate(ev.optString("date", ""));
        if (kickoff <= 0) return null;
        JSONArray comps = ev.getJSONArray("competitions");
        JSONObject comp = comps.getJSONObject(0);
        JSONArray teams = comp.getJSONArray("competitors");
        String home = null, away = null;
        for (int i = 0; i < teams.length(); i++) {
            JSONObject c = teams.getJSONObject(i);
            String name = teamName(c.optJSONObject("team"));
            if (name == null) continue;
            if ("home".equals(c.optString("homeAway"))) home = name;
            else if ("away".equals(c.optString("homeAway"))) away = name;
        }
        if (home == null || away == null) return null;
        String state = "pre";
        JSONObject status = ev.optJSONObject("status");
        if (status == null) status = comp.optJSONObject("status");
        if (status != null && status.optJSONObject("type") != null) {
            state = status.getJSONObject("type").optString("state", "pre");
        }
        return new Match(kickoff, home, away, state);
    }
    private static String teamName(JSONObject team) {
        if (team == null) return null;
        String n = team.optString("shortDisplayName", "");
        if (n.isEmpty()) n = team.optString("displayName", "");
        if (n.isEmpty()) n = team.optString("abbreviation", "");
        return clean(n);
    }
    private static String clean(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 24; i++) {
            char ch = s.charAt(i);
            if (ch >= 32 && ch != 127 && ch != '|') b.append(ch);
        }
        String t = b.toString().trim();
        return t.isEmpty() ? null : t;
    }
    static long parseDate(String s) {
        String[] patterns = {"yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                f.setLenient(false);
                return f.parse(s).getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }
    static String format(List<Match> matches, TimeZone tz, long nowMillis) {
        StringBuilder sb = new StringBuilder();
        for (Match m : matches) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(when(m, tz, nowMillis)).append("  ").append(m.home).append(" v ").append(m.away);
        }
        return sb.toString();
    }
    private static String when(Match m, TimeZone tz, long nowMillis) {
        if ("in".equals(m.state) || (m.kickoffUtc <= nowMillis && !"post".equals(m.state))) return "LIVE";
        Calendar c = Calendar.getInstance(tz, Locale.US);
        c.setTimeInMillis(m.kickoffUtc);
        return String.format(Locale.US, "%s %d %s  %02d:%02d",
                DAYS[c.get(Calendar.DAY_OF_WEEK) - 1], c.get(Calendar.DAY_OF_MONTH),
                MONTHS[c.get(Calendar.MONTH)], c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }
}
