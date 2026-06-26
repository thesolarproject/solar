package com.solar.launcher.radio.net;

import com.solar.launcher.AppVersion;
import com.solar.launcher.BuildConfig;
import com.solar.launcher.net.TlsHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Radio Browser directory — https://api.radio-browser.info
 * ponytail: de1 primary, nl1 fallback; JSON parse helpers exposed for unit tests.
 */
public final class RadioBrowserClient {
  private static final String[] BASES = {
    "https://de1.api.radio-browser.info/json",
    "https://nl1.api.radio-browser.info/json"
  };

  private final android.content.Context appCtx;
  private final OkHttpClient client;

  public static final class Station {
    public final String stationuuid;
    public final String name;
    public final String urlResolved;
    public final String countrycode;
    public final String tags;
    public final String favicon;

    public Station(String stationuuid, String name, String urlResolved, String countrycode,
        String tags, String favicon) {
      this.stationuuid = stationuuid;
      this.name = name;
      this.urlResolved = urlResolved;
      this.countrycode = countrycode;
      this.tags = tags;
      this.favicon = favicon;
    }
  }

  public static final class Country {
    public final String name;
    public final String isoCode;
    public final int stationcount;

    public Country(String name, String isoCode, int stationcount) {
      this.name = name;
      this.isoCode = isoCode;
      this.stationcount = stationcount;
    }
  }

  public static final class State {
    public final String name;
    public final String country;
    public final int stationcount;

    public State(String name, String country, int stationcount) {
      this.name = name;
      this.country = country;
      this.stationcount = stationcount;
    }
  }

  public static final class Tag {
    public final String name;
    public final int stationcount;

    public Tag(String name, int stationcount) {
      this.name = name;
      this.stationcount = stationcount;
    }
  }

  public RadioBrowserClient(android.content.Context ctx) {
    appCtx = ctx.getApplicationContext();
    client = TlsHelper.client();
  }

  /** RadioBrowserClient for tests — no Context. */
  RadioBrowserClient(OkHttpClient client) {
    appCtx = null;
    this.client = client;
  }

  public List<Country> listCountries() throws IOException {
    byte[] raw = get("/countries");
    return parseCountries(raw);
  }

  public List<State> listStates(String countrycode) throws IOException {
    String cc = normalizeCode(countrycode);
    byte[] raw = get("/states/countrycode/" + enc(cc));
    return parseStates(raw);
  }

  public List<Tag> listTags(int limit) throws IOException {
    if (limit < 1) limit = 40;
    byte[] raw = get("/tags?limit=" + limit + "&order=stationcount&reverse=true");
    return parseTags(raw);
  }

  public List<Station> searchStations(String countrycode, String state, String tag, int limit,
      int offset) throws IOException {
    if (limit < 1) limit = 40;
    if (offset < 0) offset = 0;
    StringBuilder path = new StringBuilder("/stations/search?hidebroken=true&order=clickcount&reverse=true");
    path.append("&limit=").append(limit).append("&offset=").append(offset);
    if (countrycode != null && !countrycode.trim().isEmpty()) {
      path.append("&countrycode=").append(enc(normalizeCode(countrycode)));
    }
    if (state != null && !state.trim().isEmpty()) {
      path.append("&state=").append(enc(state.trim()));
    }
    if (tag != null && !tag.trim().isEmpty()) {
      path.append("&tag=").append(enc(tag.trim()));
    }
    byte[] raw = get(path.toString());
    return parseStations(raw);
  }

  public void reportClick(String stationuuid) throws IOException {
    if (stationuuid == null || stationuuid.trim().isEmpty()) return;
    get("/url/" + enc(stationuuid.trim()));
  }

  static List<Country> parseCountries(byte[] raw) throws IOException {
    List<Country> out = new ArrayList<Country>();
    try {
      JSONArray arr = new JSONArray(new String(raw, "UTF-8"));
      for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.optJSONObject(i);
        if (o == null) continue;
        out.add(
            new Country(o.optString("name", ""), o.optString("iso_3166_1_2", ""),
                o.optInt("stationcount", 0)));
      }
    } catch (Exception e) {
      throw new IOException("countries parse failed", e);
    }
    return out;
  }

  static List<State> parseStates(byte[] raw) throws IOException {
    List<State> out = new ArrayList<State>();
    try {
      JSONArray arr = new JSONArray(new String(raw, "UTF-8"));
      for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.optJSONObject(i);
        if (o == null) continue;
        out.add(
            new State(o.optString("name", ""), o.optString("country", ""),
                o.optInt("stationcount", 0)));
      }
    } catch (Exception e) {
      throw new IOException("states parse failed", e);
    }
    return out;
  }

  static List<Tag> parseTags(byte[] raw) throws IOException {
    List<Tag> out = new ArrayList<Tag>();
    try {
      JSONArray arr = new JSONArray(new String(raw, "UTF-8"));
      for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.optJSONObject(i);
        if (o == null) continue;
        out.add(new Tag(o.optString("name", ""), o.optInt("stationcount", 0)));
      }
    } catch (Exception e) {
      throw new IOException("tags parse failed", e);
    }
    return out;
  }

  static List<Station> parseStations(byte[] raw) throws IOException {
    List<Station> out = new ArrayList<Station>();
    try {
      JSONArray arr = new JSONArray(new String(raw, "UTF-8"));
      for (int i = 0; i < arr.length(); i++) {
        Station s = parseStation(arr.optJSONObject(i));
        if (s != null) out.add(s);
      }
    } catch (Exception e) {
      throw new IOException("stations parse failed", e);
    }
    return out;
  }

  static Station parseStation(JSONObject o) {
    if (o == null) return null;
    String uuid = o.optString("stationuuid", "").trim();
    String name = o.optString("name", "").trim();
    String url = o.optString("url_resolved", "").trim();
    if (url.isEmpty()) url = o.optString("url", "").trim();
    if (uuid.isEmpty() || name.isEmpty() || url.isEmpty()) return null;
    return new Station(uuid, name, url, o.optString("countrycode", "").trim(),
        o.optString("tags", "").trim(), o.optString("favicon", "").trim());
  }

  private byte[] get(String path) throws IOException {
    TlsHelper.ensureSecurityProvider();
    IOException last = null;
    for (String base : BASES) {
      Request req =
          new Request.Builder()
              .url(base + path)
              .header("User-Agent", userAgent())
              .header("Accept", "application/json")
              .build();
      try {
        Response resp = client.newCall(req).execute();
        try {
          if (!resp.isSuccessful() || resp.body() == null) {
            throw new IOException("HTTP " + resp.code() + " for " + base + path);
          }
          return resp.body().bytes();
        } finally {
          if (resp.body() != null) resp.body().close();
        }
      } catch (IOException e) {
        last = e;
      }
    }
    throw last != null ? last : new IOException("All Radio Browser mirrors failed");
  }

  private String userAgent() {
    String ver =
        appCtx != null
            ? AppVersion.installedVersionName(appCtx)
            : (BuildConfig.VERSION_NAME != null ? BuildConfig.VERSION_NAME : "dev");
    return "Solar/" + ver;
  }

  private static String enc(String s) throws IOException {
    return URLEncoder.encode(s, "UTF-8");
  }

  private static String normalizeCode(String code) {
    return code == null ? "" : code.trim().toUpperCase(Locale.US);
  }
}
