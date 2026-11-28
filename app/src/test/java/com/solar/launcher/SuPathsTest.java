package com.solar.launcher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class SuPathsTest {
    @Rule public final TemporaryFolder tmp = new TemporaryFolder();

    private File executable(File dir, String name) throws IOException {
        File f = new File(dir, name);
        assertTrue(f.createNewFile());
        assertTrue(f.setExecutable(true));
        return f;
    }

    @Test
    public void dropsMissingAbsolutePaths() throws IOException {
        File xbin = tmp.newFolder("xbin");
        String present = executable(xbin, "su").getAbsolutePath();
        String missing = new File(tmp.newFolder("bin"), "su").getAbsolutePath();

        assertArrayEquals(new String[] {present},
                SuPaths.filter(new String[] {present, missing}, null));
    }

    @Test
    public void keepsBareNameOnlyWhenOnSearchPath() throws IOException {
        File empty = tmp.newFolder("empty");
        File xbin = tmp.newFolder("xbin");
        executable(xbin, "su");

        assertArrayEquals(new String[] {"su"}, SuPaths.filter(new String[] {"su"},
                empty.getAbsolutePath() + File.pathSeparator + xbin.getAbsolutePath()));
        assertArrayEquals(new String[0],
                SuPaths.filter(new String[] {"su"}, empty.getAbsolutePath()));
        assertArrayEquals(new String[0], SuPaths.filter(new String[] {"su"}, null));
    }

    @Test
    public void ignoresDirectories() throws IOException {
        File bin = tmp.newFolder("bin");
        File dir = new File(bin, "su");
        assertTrue(dir.mkdir());

        assertArrayEquals(new String[0],
                SuPaths.filter(new String[] {dir.getAbsolutePath(), "su"}, bin.getAbsolutePath()));
    }
}
