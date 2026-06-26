package com.solar.launcher.radio.net;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RadioBrowserClientTest {

  private static final String STATIONS_JSON =
      "[{\"stationuuid\":\"abc-123\",\"name\":\"Test FM\",\"url_resolved\":\"http://stream/test\","
          + "\"countrycode\":\"US\",\"tags\":\"rock,pop\",\"favicon\":\"http://icon.png\"},"
          + "{\"stationuuid\":\"\",\"name\":\"skip\",\"url\":\"http://x\"}]";

  private static final String COUNTRIES_JSON =
      "[{\"name\":\"United States\",\"iso_3166_1_2\":\"US\",\"stationcount\":12000}]";

  private static final String TAGS_JSON =
      "[{\"name\":\"jazz\",\"stationcount\":500},{\"name\":\"news\",\"stationcount\":300}]";

  @Test
  public void parseStations_skipsIncompleteRows() throws Exception {
    List<RadioBrowserClient.Station> list =
        RadioBrowserClient.parseStations(STATIONS_JSON.getBytes("UTF-8"));
    assertEquals(1, list.size());
    RadioBrowserClient.Station s = list.get(0);
    assertEquals("abc-123", s.stationuuid);
    assertEquals("Test FM", s.name);
    assertEquals("http://stream/test", s.urlResolved);
    assertEquals("US", s.countrycode);
    assertEquals("rock,pop", s.tags);
    assertEquals("http://icon.png", s.favicon);
  }

  @Test
  public void parseStation_fallsBackToUrlField() {
    org.json.JSONObject o = new org.json.JSONObject();
    try {
      o.put("stationuuid", "u1");
      o.put("name", "Fallback");
      o.put("url", "http://direct");
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    RadioBrowserClient.Station s = RadioBrowserClient.parseStation(o);
    assertNotNull(s);
    assertEquals("http://direct", s.urlResolved);
  }

  @Test
  public void parseCountries() throws Exception {
    List<RadioBrowserClient.Country> list =
        RadioBrowserClient.parseCountries(COUNTRIES_JSON.getBytes("UTF-8"));
    assertEquals(1, list.size());
    assertEquals("US", list.get(0).isoCode);
    assertTrue(list.get(0).stationcount > 0);
  }

  @Test
  public void parseTags() throws Exception {
    List<RadioBrowserClient.Tag> list =
        RadioBrowserClient.parseTags(TAGS_JSON.getBytes("UTF-8"));
    assertEquals(2, list.size());
    assertEquals("jazz", list.get(0).name);
  }
}
