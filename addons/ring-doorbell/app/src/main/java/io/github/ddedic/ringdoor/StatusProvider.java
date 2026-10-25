package io.github.ddedic.ringdoor;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * The launcher's status_widget (docs/specs/status.md): a round door icon with a badge for rings not seen yet.
 * OK opens the preview. Only the count and the doorbell's name leave the app; no token, no picture.
 */
public final class StatusProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.ddedic.ringdoor.status";
    static final Uri STATUS = Uri.parse("content://" + AUTHORITY + "/status");
    private static final String[] COLUMNS = {"text", "icon", "badge", "description", "intent"};

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (!"status".equals(uri.getLastPathSegment())) return null;
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        Store store = new Store(getContext());
        if (store.configured() && store.enabled()) {
            int unseen = store.unseen();
            String description = unseen > 0 ? getContext().getResources().getQuantityString(R.plurals.unseen, unseen, unseen)
                    : getContext().getString(R.string.no_new_rings);
            String intent = new Intent(getContext(), PreviewActivity.class).toUri(Intent.URI_INTENT_SCHEME);
            // An unseen ring names its time on the pill ("Rang 22:41"); a seen one leaves the plain door icon.
            Store.Event ring = unseen > 0 ? store.newestRing() : null;
            String text = ring == null ? "" : getContext().getString(R.string.pill_rang, Ui.clock(getContext(), ring.at));
            cursor.addRow(new Object[]{text, "door", String.valueOf(unseen), description, intent});
        }
        cursor.setNotificationUri(getContext().getContentResolver(), STATUS);
        return cursor;
    }

    /** "refresh": Home came to the front, so make sure the push connection is up (it starts once). */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("refresh".equals(method)) DoorbellService.start(getContext());
        return null;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
