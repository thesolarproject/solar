package com.solar.launcher;

import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * 2026-11-26 — Writes a FAT12/16/32 volume label without dosfstools.
 * Layman: names the player's card "SD" on a bare ROM, so Finder/Explorer show a familiar drive.
 * Tech: updates the BPB label (0x2B FAT12/16, 0x47 FAT32), the FAT32 backup boot sector, and
 *   the root-directory volume-label entry. Runs as root (UmsEnabler / app_process) because the
 *   vold block nodes are root-only. Refuses anything that is not a FAT boot sector, so a wrong
 *   path can only fail — it never formats or touches a non-FAT disk.
 * Reversal: drop the UmsEnabler.labelExports() call and this file; labels already written stay.
 */
public final class FatLabel {

    private FatLabel() {}

    /** @return true when the label was written; false on any non-FAT or unreadable device. */
    static boolean apply(String devPath, String label) {
        if (devPath == null || devPath.length() == 0 || label == null) return false;
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile(devPath, "rw");
            byte[] boot = new byte[512];
            f.seek(0L);
            f.readFully(boot);
            if ((boot[510] & 0xFF) != 0x55 || (boot[511] & 0xFF) != 0xAA) return false;
            int bytesPerSector = le16(boot, 11);
            if (bytesPerSector < 512 || bytesPerSector > 4096
                    || (bytesPerSector & (bytesPerSector - 1)) != 0) return false;
            int sectorsPerCluster = boot[13] & 0xFF;
            int reserved = le16(boot, 14);
            int fats = boot[16] & 0xFF;
            int rootEntries = le16(boot, 17);
            long fatSize = le16(boot, 22);
            if (fatSize == 0) fatSize = le32(boot, 36) & 0xFFFFFFFFL;
            long rootCluster = le32(boot, 44) & 0xFFFFFFFFL;
            int backupBoot = le16(boot, 50);
            boolean fat32 = rootEntries == 0 && fatSize > 0
                    && isAscii(boot, 82, "FAT32");
            if (!fat32 && !isAscii(boot, 54, "FAT12") && !isAscii(boot, 54, "FAT16")) {
                return false;
            }
            if (sectorsPerCluster == 0 || reserved == 0 || fats == 0 || fatSize == 0) {
                return false;
            }
            long dataSector = reserved + (long) fats * fatSize;
            long rootOffset;
            int rootBytes;
            if (fat32) {
                if (rootCluster < 2) return false;
                rootOffset = (dataSector + (rootCluster - 2) * sectorsPerCluster)
                        * bytesPerSector;
                rootBytes = sectorsPerCluster * bytesPerSector;
            } else {
                rootOffset = dataSector * bytesPerSector;
                rootBytes = rootEntries * 32;
            }
            if (rootBytes <= 0) return false;
            byte[] dir = new byte[Math.min(rootBytes, 64 * 1024)];
            f.seek(rootOffset);
            f.readFully(dir);
            int slot = findLabelSlot(dir);
            if (slot < 0) return false;
            byte[] entry = new byte[32];
            f.seek(rootOffset + slot);
            f.readFully(entry);
            if ((entry[11] & 0x3F) != 0x08) {
                Arrays.fill(entry, (byte) 0);
                entry[11] = 0x08;
            }
            byte[] name = paddedLabel(label);
            System.arraycopy(name, 0, entry, 0, 11);
            f.seek(rootOffset + slot);
            f.write(entry);
            int bpbOffset = fat32 ? 71 : 43;
            f.seek(bpbOffset);
            f.write(name);
            if (fat32 && backupBoot > 0 && backupBoot != 0xFFFF
                    && backupBoot != 0) {
                f.seek((long) backupBoot * bytesPerSector + bpbOffset);
                f.write(name);
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (f != null) {
                try {
                    f.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Existing volume-label entry, or a free slot when the root directory has none. */
    private static int findLabelSlot(byte[] dir) {
        int free = -1;
        for (int i = 0; i + 32 <= dir.length; i += 32) {
            int first = dir[i] & 0xFF;
            int attr = dir[i + 11] & 0xFF;
            if (first == 0x00) {
                return free >= 0 ? free : i;
            }
            if (first == 0xE5) {
                if (free < 0) free = i;
                continue;
            }
            if ((attr & 0x3F) == 0x08) return i;
        }
        return free;
    }

    /** Uppercase ASCII, space padded to the 11-byte FAT name field. */
    private static byte[] paddedLabel(String label) {
        String upper = label.toUpperCase(java.util.Locale.US);
        byte[] out = new byte[11];
        Arrays.fill(out, (byte) ' ');
        int n = Math.min(11, upper.length());
        for (int i = 0; i < n; i++) {
            char c = upper.charAt(i);
            out[i] = (byte) (c >= 0x20 && c < 0x7F ? c : ' ');
        }
        return out;
    }

    private static boolean isAscii(byte[] b, int off, String want) {
        if (off + want.length() > b.length) return false;
        for (int i = 0; i < want.length(); i++) {
            if ((b[off + i] & 0xFF) != want.charAt(i)) return false;
        }
        return true;
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long le32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }
}
