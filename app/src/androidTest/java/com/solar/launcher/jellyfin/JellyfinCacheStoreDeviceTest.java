package com.solar.launcher.jellyfin;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class JellyfinCacheStoreDeviceTest {
    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        JellyfinCacheStore.getInstance(ctx).clearArtists();
    }

    @After
    public void tearDown() {
        JellyfinCacheStore.getInstance(ctx).clearArtists();
    }

    @Test
    public void loadArtists_empty_returnsEmptyList() {
        List<JellyfinArtist> artists = JellyfinCacheStore.getInstance(ctx).loadArtists();
        assertTrue(artists != null);
        assertTrue(artists.isEmpty());
    }

    @Test
    public void saveAndLoadArtists_preservesDataAndSortsByNameAsc() {
        List<JellyfinArtist> artists = new ArrayList<JellyfinArtist>();
        JellyfinArtist a1 = new JellyfinArtist();
        a1.id = "2";
        a1.name = "Zebra";
        a1.albumCount = 5;
        a1.coverArtId = "cover2";
        a1.indexLetter = "Z";
        artists.add(a1);

        JellyfinArtist a2 = new JellyfinArtist();
        a2.id = "1";
        a2.name = "Apple";
        a2.albumCount = 2;
        a2.coverArtId = "cover1";
        a2.indexLetter = "A";
        artists.add(a2);

        JellyfinCacheStore store = JellyfinCacheStore.getInstance(ctx);
        store.saveArtists(artists);

        List<JellyfinArtist> loaded = store.loadArtists();
        if (loaded.size() != 2) throw new AssertionError("size");

        // They should be sorted by name ASC
        if (!"Apple".equals(loaded.get(0).name)) throw new AssertionError("sort0");
        if (!"1".equals(loaded.get(0).id)) throw new AssertionError("id0");
        if (2 != loaded.get(0).albumCount) throw new AssertionError("count0");
        if (!"cover1".equals(loaded.get(0).coverArtId)) throw new AssertionError("cover0");
        if (!"A".equals(loaded.get(0).indexLetter)) throw new AssertionError("index0");

        if (!"Zebra".equals(loaded.get(1).name)) throw new AssertionError("sort1");
        if (!"2".equals(loaded.get(1).id)) throw new AssertionError("id1");
    }

    @Test
    public void saveArtists_handlesNullValuesGracefully() {
        List<JellyfinArtist> artists = new ArrayList<JellyfinArtist>();
        JellyfinArtist a1 = new JellyfinArtist();
        a1.id = "3";
        a1.name = "Artist Three";
        a1.albumCount = 0;
        a1.coverArtId = null;
        a1.indexLetter = null;
        artists.add(a1);

        JellyfinCacheStore store = JellyfinCacheStore.getInstance(ctx);
        store.saveArtists(artists);

        List<JellyfinArtist> loaded = store.loadArtists();
        if (loaded.size() != 1) throw new AssertionError("size");
        if (!"Artist Three".equals(loaded.get(0).name)) throw new AssertionError("name");
        if (!"".equals(loaded.get(0).coverArtId)) throw new AssertionError("cover null default");
        if (!"#".equals(loaded.get(0).indexLetter)) throw new AssertionError("index null default");
    }

    @Test
    public void clearArtists_removesAllRows() {
        List<JellyfinArtist> artists = new ArrayList<JellyfinArtist>();
        JellyfinArtist a1 = new JellyfinArtist();
        a1.id = "1";
        a1.name = "Artist One";
        artists.add(a1);

        JellyfinCacheStore store = JellyfinCacheStore.getInstance(ctx);
        store.saveArtists(artists);
        if (store.loadArtists().size() != 1) throw new AssertionError("save failed");

        store.clearArtists();
        if (store.loadArtists().size() != 0) throw new AssertionError("clear failed");
    }
}
