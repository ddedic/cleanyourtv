package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class FeedRowsTest {
    private static FeaturedShelf.Item item(String title) {
        return new FeaturedShelf.Item(title, "", "", "", "", "", "", "");
    }

    @Test public void picturesComeDownToScreenWidthInPowersOfTwo() {
        assertEquals(1, FeedRows.sampleSize(1280, 1920));
        assertEquals(1, FeedRows.sampleSize(1920, 1920));
        assertEquals(2, FeedRows.sampleSize(3840, 1920));
        assertEquals(4, FeedRows.sampleSize(7680, 1920));
        assertEquals(1, FeedRows.sampleSize(0, 1920));
    }

    @Test public void longTextIsCutWithAnEllipsis() {
        assertEquals("Short", FeedRows.clip("Short", 10));
        assertEquals("Abcdefghi…", FeedRows.clip("Abcdefghijklmnop", 10));
    }

    @Test public void aRefreshWithTheSameTitlesKeepsTheRotation() {
        List<FeaturedShelf.Item> a = Arrays.asList(item("Dune"), item("Severance"));
        assertTrue(FeedRows.sameTitles(a, Arrays.asList(item("Dune"), item("Severance"))));
        assertFalse(FeedRows.sameTitles(a, Arrays.asList(item("Severance"), item("Dune"))));
        assertFalse(FeedRows.sameTitles(a, Arrays.asList(item("Dune"))));
    }
}
