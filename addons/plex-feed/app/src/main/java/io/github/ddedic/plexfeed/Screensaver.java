package io.github.ddedic.plexfeed;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * The TV's screensaver, switched from the setup screen: on makes FeedDream the screensaver and sets how long the TV
 * waits; off puts back what the TV had before. Needs WRITE_SECURE_SETTINGS, granted once over ADB:
 * `pm grant io.github.ddedic.plexfeed android.permission.WRITE_SECURE_SETTINGS`. Philips firmware has no screensaver
 * page that Android's own intent reaches, so this is the way to change it on the TV.
 */
final class Screensaver {
    static final int[] MINUTES = {5, 10, 15, 30, 60};
    private static final String ENABLED = "screensaver_enabled";
    private static final String COMPONENTS = "screensaver_components";

    private final Context context;
    private final SharedPreferences prefs;

    Screensaver(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("screensaver", Context.MODE_PRIVATE);
    }

    boolean canChange() {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    private String ours() {
        return new ComponentName(context, FeedDream.class).flattenToString();
    }

    boolean isOn() {
        ContentResolver resolver = context.getContentResolver();
        return Settings.Secure.getInt(resolver, ENABLED, 0) == 1
                && ours().equals(Settings.Secure.getString(resolver, COMPONENTS));
    }

    int minutes() {
        return prefs.getInt("minutes", 15);
    }

    /** Turns the screensaver on with this wait, remembering the TV's own values the first time. */
    void turnOn(int minutes) {
        ContentResolver resolver = context.getContentResolver();
        if (!prefs.contains("old_timeout")) {
            prefs.edit()
                    .putInt("old_timeout", Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, 604800000))
                    .putInt("old_enabled", Settings.Secure.getInt(resolver, ENABLED, 0))
                    .putString("old_components", stringOr(Settings.Secure.getString(resolver, COMPONENTS)))
                    .apply();
        }
        prefs.edit().putInt("minutes", minutes).apply();
        Settings.Secure.putString(resolver, COMPONENTS, ours());
        Settings.Secure.putInt(resolver, ENABLED, 1);
        Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, minutes * 60000);
    }

    /** Puts back the values the TV had before turnOn. */
    void turnOff() {
        ContentResolver resolver = context.getContentResolver();
        Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, prefs.getInt("old_timeout", 604800000));
        Settings.Secure.putInt(resolver, ENABLED, prefs.getInt("old_enabled", 0));
        String old = prefs.getString("old_components", "");
        if (!old.isEmpty()) Settings.Secure.putString(resolver, COMPONENTS, old);
        prefs.edit().remove("old_timeout").remove("old_enabled").remove("old_components").apply();
    }

    private static String stringOr(String value) {
        return value == null ? "" : value;
    }
}
