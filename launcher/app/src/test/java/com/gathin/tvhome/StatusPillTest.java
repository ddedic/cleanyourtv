package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StatusPillTest {
    @Test public void theTextIsOneShortLine() {
        assertEquals("21.4° → 22°", StatusPill.clean("  21.4°\n→   22° "));
        assertEquals("", StatusPill.clean(null));
        String cut = StatusPill.clean("Living room 21.4° heating up to 22.5° until 18:00");
        assertEquals(StatusPill.MAX_TEXT, cut.length());
        assertEquals('…', cut.charAt(cut.length() - 1));
    }

    @Test public void onlyTheLaunchersOwnIconsAreShown() {
        assertEquals(R.drawable.ic_thermostat, StatusPill.icon("thermostat"));
        assertEquals(R.drawable.ic_flame, StatusPill.icon("flame"));
        assertEquals(0, StatusPill.icon("android.resource://evil/raw/x"));
        assertEquals(0, StatusPill.icon(null));
    }
}
