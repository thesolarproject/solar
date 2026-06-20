package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class QueueBrowseWindowTest {

    @Test
    public void windowStart_smallQueueIsZero() {
        assertEquals(0, QueueBrowseWindow.windowStart(3, 7, 3, 2));
    }

    @Test
    public void windowStart_centersOnFocus() {
        assertEquals(2, QueueBrowseWindow.windowStart(5, 20, 3, 2));
    }

    @Test
    public void windowStart_clampsAtEnd() {
        int count = 20;
        int visible = 3;
        int buffer = 2;
        int windowSize = QueueBrowseWindow.windowSize(visible, buffer);
        assertEquals(count - windowSize, QueueBrowseWindow.windowStart(19, count, visible, buffer));
    }
}
