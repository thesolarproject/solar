package com.solar.launcher.photos;

import java.io.File;
import java.util.Collections;
import java.util.List;

/** Full-screen gallery navigation state — UI binds current file and handles wheel next/prev. */
public final class PhotoViewer {
    private List<File> images = Collections.emptyList();
    private int index;

    public void setFolder(List<File> files, int startIndex) {
        if (files == null || files.isEmpty()) {
            images = Collections.emptyList();
            index = 0;
            return;
        }
        images = files;
        if (startIndex < 0) startIndex = 0;
        if (startIndex >= images.size()) startIndex = images.size() - 1;
        index = startIndex;
    }

    public File currentFile() {
        if (images.isEmpty() || index < 0 || index >= images.size()) return null;
        return images.get(index);
    }

    public int getIndex() {
        return index;
    }

    public int getCount() {
        return images.size();
    }

    public boolean hasNext() {
        return index + 1 < images.size();
    }

    public boolean hasPrev() {
        return index > 0;
    }

    /** Advances index; returns new current file or null at end. */
    public File next() {
        if (!hasNext()) return null;
        index++;
        return currentFile();
    }

    /** Steps back; returns new current file or null at start. */
    public File prev() {
        if (!hasPrev()) return null;
        index--;
        return currentFile();
    }
}
