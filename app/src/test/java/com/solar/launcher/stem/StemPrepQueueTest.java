package com.solar.launcher.stem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;

import org.junit.Test;

public class StemPrepQueueTest {
    @Test
    public void preservesFifoAndDeduplicatesPaths() throws Exception {
        File first = File.createTempFile("stem-queue-first-", ".mp3");
        File second = File.createTempFile("stem-queue-second-", ".mp3");
        first.deleteOnExit();
        second.deleteOnExit();
        ArrayList<File> initial = new ArrayList<File>();
        initial.add(first);
        initial.add(first);
        initial.add(second);

        StemPrepQueue queue = new StemPrepQueue(initial);
        assertEquals(2, queue.size());
        assertEquals(first.getAbsolutePath(), queue.poll().getAbsolutePath());
        assertEquals(second.getAbsolutePath(), queue.poll().getAbsolutePath());
        assertTrue(queue.isEmpty());
        assertNull(queue.poll());
        assertFalse(queue.enqueue(null));
    }
}
