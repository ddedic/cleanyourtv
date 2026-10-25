package io.github.ddedic.netatmo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * The launcher's status_widget (docs/specs/status.md): one row with the main room's temperature and target.
 * Only that text leaves the app; the credentials and tokens never do.
 */
public final class StatusProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.ddedic.netatmo.status";
    static final Uri STATUS = Uri.parse("content://" + AUTHORITY + "/status");
    private static final String[] COLUMNS = {"text", "icon", "description", "intent"};

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (!"status".equals(uri.getLastPathSegment())) return null;
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        Store store = new Store(getContext());
        Status status = store.signedIn() ? store.status() : null;
        Status.Room room = status == null ? null : status.main(store.room());
        // Older than an hour: better no number than a wrong one.
        boolean fresh = status != null && System.currentTimeMillis() - status.fetchedAt < 60 * 60 * 1000L;
        String text = fresh ? Status.pill(room) : "";
        if (!text.isEmpty()) {
            boolean heating = status.heating();
            String description = room.name + " " + Status.degrees(room.measured)
                    + (heating ? ", heating to " : ", set to ") + Status.degrees(room.target);
            String intent = new Intent(getContext(), PanelActivity.class).toUri(Intent.URI_INTENT_SCHEME);
            cursor.addRow(new Object[]{text, heating ? "flame" : "thermostat", description, intent});
        }
        cursor.setNotificationUri(getContext().getContentResolver(), STATUS);
        return cursor;
    }

    /** "refresh": fetch again if the status is older than five minutes; returns at once (notifyChange follows). */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("refresh".equals(method)) Fetcher.refresh(getContext(), false, null);
        return null;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
