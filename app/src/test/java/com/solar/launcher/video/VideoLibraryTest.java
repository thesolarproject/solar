package com.solar.launcher.video;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class VideoLibraryTest {

    @After
    public void tearDown() {
        VideoLibrary.rootOverride = null;
        VideoLibrary.deviceRootOverride = null;
    }

    @Test
    public void extensionFilter() {
        VideoLibrary.selfCheck();
        if (!VideoLibrary.isVideoFileName("a.MP4")) throw new AssertionError("mp4");
        if (VideoLibrary.isVideoFileName("readme.md")) throw new AssertionError("md");
    }

    @Test
    public void scanAll_findsNestedVideos() throws IOException {
        File root = File.createTempFile("videolib", "");
        if (!root.delete()) throw new AssertionError("delete");
        if (!root.mkdir()) throw new AssertionError("mkdir");
        File nested = new File(root, "folder");
        if (!nested.mkdir()) throw new AssertionError("nested");
        touch(new File(root, "top.mp4"));
        touch(new File(nested, "inner.mkv"));
        touch(new File(root, "skip.jpg"));

        VideoLibrary.rootOverride = root;
        List<File> found = VideoLibrary.scanAll();
        if (found.size() != 2) throw new AssertionError("count " + found.size());
    }

    @Test
    public void listInFolder_nonRecursive() throws IOException {
        File root = File.createTempFile("videolib2", "");
        if (!root.delete()) throw new AssertionError("delete");
        if (!root.mkdir()) throw new AssertionError("mkdir");
        File sub = new File(root, "sub");
        if (!sub.mkdir()) throw new AssertionError("sub");
        touch(new File(root, "one.avi"));
        touch(new File(sub, "two.mov"));

        List<File> inRoot = VideoLibrary.listInFolder(root);
        if (inRoot.size() != 1) throw new AssertionError("root count");
        if (!inRoot.get(0).getName().equals("one.avi")) throw new AssertionError("name");
    }

    private static void touch(File f) throws IOException {
        if (!f.createNewFile()) throw new IOException("create " + f);
        java.io.FileOutputStream out = new java.io.FileOutputStream(f);
        out.write(1);
        out.close();
    }

    @Test
    public void browseParent_walksUpWithinDeviceRoot() throws IOException {
        File device = File.createTempFile("videodev", "");
        if (!device.delete()) throw new AssertionError("delete");
        if (!device.mkdir()) throw new AssertionError("mkdir");
        File videos = new File(device, "Videos");
        if (!videos.mkdir()) throw new AssertionError("videos");
        File nested = new File(videos, "clips");
        if (!nested.mkdir()) throw new AssertionError("nested");

        VideoLibrary.deviceRootOverride = device;
        VideoLibrary.rootOverride = videos;

        if (!VideoLibrary.isBrowsablePath(videos)) throw new AssertionError("videos browsable");
        if (!VideoLibrary.isBrowsablePath(nested)) throw new AssertionError("nested browsable");
        if (VideoLibrary.browseParent(device) != null) throw new AssertionError("device has no parent");

        File p1 = VideoLibrary.browseParent(nested);
        if (p1 == null || !p1.equals(videos)) throw new AssertionError("nested parent");
        File p2 = VideoLibrary.browseParent(videos);
        if (p2 == null || !p2.equals(device)) throw new AssertionError("videos parent");
    }

    @Test
    public void listChildFoldersWithVideos_findsSiblings() throws IOException {
        File device = File.createTempFile("videodev2", "");
        if (!device.delete()) throw new AssertionError("delete");
        if (!device.mkdir()) throw new AssertionError("mkdir");
        File videos = new File(device, "Videos");
        if (!videos.mkdir()) throw new AssertionError("videos");
        File downloads = new File(device, "Download");
        if (!downloads.mkdir()) throw new AssertionError("downloads");
        touch(new File(downloads, "movie.mp4"));

        VideoLibrary.deviceRootOverride = device;
        VideoLibrary.rootOverride = videos;

        List<File> atDevice = VideoLibrary.listChildFoldersWithVideos(device);
        if (atDevice.size() != 1) throw new AssertionError("device children " + atDevice.size());
        if (!atDevice.get(0).getName().equals("Download")) throw new AssertionError("expected Download");
    }
}
