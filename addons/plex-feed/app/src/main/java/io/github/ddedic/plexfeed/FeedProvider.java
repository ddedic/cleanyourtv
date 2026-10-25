package io.github.ddedic.plexfeed;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * The featured feed (docs/specs/feed.md in the Clean Your TV repo): content://io.github.ddedic.plexfeed.feed/items,
 * its pictures under /art/, and call("refresh"). Only titles, text and pictures leave this app.
 */
public final class FeedProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.ddedic.plexfeed.feed";
    static final Uri ITEMS = Uri.parse("content://" + AUTHORITY + "/items");
    static final String PLEX_APP = "com.plexapp.android";
    private static final String[] COLUMNS = {"title", "subtitle", "summary", "badge", "image", "intent", "package", "action"};

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (!"items".equals(uri.getLastPathSegment())) return null;
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        List<JSONObject> items = new Store(getContext()).readItems();
        for (JSONObject item : items) {
            String art = item.optString("art");
            String link = item.optString("link");
            String intent = link.isEmpty() ? ""
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(PLEX_APP).toUri(Intent.URI_INTENT_SCHEME);
            cursor.addRow(new Object[]{item.optString("title"), item.optString("subtitle"), item.optString("summary"),
                    item.optString("badge"), art.isEmpty() ? "" : "content://" + AUTHORITY + "/art/" + art, intent,
                    PLEX_APP, "Open Plex"});
        }
        cursor.setNotificationUri(getContext().getContentResolver(), ITEMS);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> path = uri.getPathSegments();
        if (!"r".equals(mode) || path.size() != 2 || !"art".equals(path.get(0))
                || !path.get(1).matches("a[0-9a-f]{1,8}\\.jpg")) throw new FileNotFoundException(uri.toString());
        File file = new File(new Store(getContext()).artDir(), path.get(1));
        if (!file.isFile()) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** "refresh": fetch again if the items are older than 15 minutes; returns at once (notifyChange follows). */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("refresh".equals(method)) Fetcher.refresh(getContext(), false, null);
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "items".equals(uri.getLastPathSegment()) ? "vnd.android.cursor.dir/vnd.plexfeed.item" : "image/jpeg";
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
