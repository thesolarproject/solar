package com.solar.launcher.youtube;

import org.junit.Test;

import java.util.Calendar;

import static org.junit.Assert.assertEquals;

public class YouTubeQuotaTrackerTest {

    @Test
    public void exposesDocumentedOperationEstimates() {
        assertEquals(101, YouTubeQuotaTracker.estimateCost(
                YouTubeQuotaTracker.Operation.SEARCH));
        assertEquals(1, YouTubeQuotaTracker.estimateCost(
                YouTubeQuotaTracker.Operation.POPULAR));
        assertEquals(1, YouTubeQuotaTracker.estimateCost(
                YouTubeQuotaTracker.Operation.COMMENTS));
    }

    @Test
    public void dayStampUsesLocalCalendarDay() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(2026, Calendar.JULY, 28, 12, 0, 0);
        assertEquals(20260728,
                YouTubeQuotaTracker.dayStamp(calendar.getTimeInMillis()));
    }
}
