package com.solar.launcher;

import org.junit.Test;

import java.io.File;

public class StreamCacheRootTest {

    @Test
    public void hasSpace_trueWhenDirHasRoom() throws Exception {
        File dir = File.createTempFile("streamroot", "");
        if (!dir.delete()) throw new AssertionError("delete");
        if (!dir.mkdir()) throw new AssertionError("mkdir");
        if (!StreamCacheRoot.hasSpace(dir, 1)) throw new AssertionError("space");
    }

    @Test
    public void hasSpace_falseForNull() {
        if (StreamCacheRoot.hasSpace(null, 1)) throw new AssertionError("null");
    }
}
