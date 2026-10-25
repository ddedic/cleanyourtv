package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Intent;
import android.database.Cursor;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/** featured_feed: the feed's rows as items, and the checks on them (docs/specs/feed.md). */
final class FeedRows {
    private FeedRows() { }

    /** The rows of a feed query, in order, without the ones that have no title; closes the cursor. */
    static List<FeaturedShelf.Item> read(Cursor cursor) {
        List<FeaturedShelf.Item> items = new ArrayList<>();
        if (cursor == null) return items;
        try {
            int title = cursor.getColumnIndex("title");
            if (title < 0) return items;
            int subtitle = cursor.getColumnIndex("subtitle"), summary = cursor.getColumnIndex("summary");
            int badge = cursor.getColumnIndex("badge"), image = cursor.getColumnIndex("image");
            int intent = cursor.getColumnIndex("intent"), pkg = cursor.getColumnIndex("package");
            int action = cursor.getColumnIndex("action");
            while (cursor.moveToNext() && items.size() < FeaturedShelf.MAX_ITEMS) {
                String name = clip(string(cursor, title), 80);
                if (name.isEmpty()) continue;
                items.add(new FeaturedShelf.Item(name, clip(string(cursor, subtitle), 80),
                        clip(string(cursor, summary), 400), clip(string(cursor, badge), 32), string(cursor, image),
                        string(cursor, intent), string(cursor, pkg), clip(string(cursor, action), 24)));
            }
        } catch (RuntimeException broken) {
            // A feed that breaks mid-read shows what came before.
        } finally {
            cursor.close();
        }
        return items;
    }

    private static String string(Cursor cursor, int column) {
        if (column < 0 || cursor.isNull(column)) return "";
        String value = cursor.getString(column);
        return value == null ? "" : value.trim();
    }

    static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1).trim() + "…";
    }

    /** Same titles in the same order: a refresh that changed nothing keeps the rotation where it is. */
    static boolean sameTitles(List<FeaturedShelf.Item> a, List<FeaturedShelf.Item> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).title.equals(b.get(i).title)) return false;
        return true;
    }

    /**
     * featured_tint: the picture's colour from a few of its pixels, each weighted by how saturated and bright it
     * is, so a poster's grey or black background does not drown its colour. Neutral grey when there is none.
     */
    static int accent(int[] pixels) {
        double r = 0, g = 0, b = 0, total = 0;
        for (int p : pixels) {
            int pr = (p >> 16) & 0xFF, pg = (p >> 8) & 0xFF, pb = p & 0xFF;
            int max = Math.max(pr, Math.max(pg, pb)), min = Math.min(pr, Math.min(pg, pb));
            if (max == 0) continue;
            double weight = ((max - min) / (double) max) * (max / 255.0);
            r += pr * weight;
            g += pg * weight;
            b += pb * weight;
            total += weight;
        }
        if (total < 0.05) return 0xFF808080;
        return vivid((int) (r / total), (int) (g / total), (int) (b / total));
    }

    /** Pushes an averaged colour away from grey, since averaging pixels washes it out. */
    private static int vivid(int r, int g, int b) {
        int grey = (r + g + b) / 3;
        return 0xFF000000 | (boost(r, grey) << 16) | (boost(g, grey) << 8) | boost(b, grey);
    }

    private static int boost(int channel, int grey) {
        return Math.max(0, Math.min(255, Math.round(grey + (channel - grey) * 1.6f)));
    }

    /** The colour darkened to `amount` of its brightness, with this alpha: the shelf's tinted gradient. */
    static int shade(int color, float amount, int alpha) {
        int r = Math.round(((color >> 16) & 0xFF) * amount), g = Math.round(((color >> 8) & 0xFF) * amount);
        int b = Math.round((color & 0xFF) * amount);
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    /** The colour lifted towards white, readable as small text on the dark shelf. */
    static int light(int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        return 0xFF000000 | (lift(r) << 16) | (lift(g) << 8) | lift(b);
    }

    private static int lift(int channel) {
        return channel + Math.round((255 - channel) * 0.55f);
    }

    /** The power-of-two step that brings a picture down to at most maxWidth. */
    static int sampleSize(int width, int maxWidth) {
        int sample = 1;
        if (width <= 0 || maxWidth <= 0) return sample;
        while (width / (sample * 2) >= maxWidth) sample *= 2;
        return sample;
    }

    /**
     * The item's intent, parsed from its intent: URI. Null when it is missing or broken, or when it would open one
     * of this launcher's own screens. No selector and no URI grants: the feed app can only ask for an ordinary start.
     */
    static Intent parseIntent(String uri, String ownPackage) {
        if (uri == null || uri.isEmpty()) return null;
        Intent intent;
        try {
            intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
        } catch (URISyntaxException | RuntimeException broken) {
            return null;
        }
        intent.setSelector(null);
        intent.removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        ComponentName component = intent.getComponent();
        if (ownPackage.equals(intent.getPackage())
                || component != null && ownPackage.equals(component.getPackageName())) return null;
        return intent;
    }
}
