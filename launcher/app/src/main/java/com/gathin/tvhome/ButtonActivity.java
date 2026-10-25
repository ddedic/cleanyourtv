package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

/**
 * Philips remote buttons whose app was disabled: the firmware's key handler (org.droidtv GlobalKeyRcv) sends a plain
 * action with no package, LAUNCH_RAKUTEN_TV for the Rakuten TV button and LAUNCH_APP_GALLERY for the Philips
 * button, and with that app gone nothing answers. An alias per button answers instead and opens the app picked in
 * Launcher settings. The aliases stay disabled until a button gets an app, so a TV whose own app is still there
 * never sees a second candidate and a chooser; no key is filtered, so volume repeat is untouched.
 */
public class ButtonActivity extends Activity {
    /** [key, alias class name]: the buttons Launcher settings offers. */
    static final String[][] BUTTONS = {{"rakuten", ".RakutenButton"}, {"gallery", ".AppGalleryButton"}};
    private static final String PREFIX = "button_";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            ComponentName alias = getIntent().getComponent();
            String key = alias == null ? null : keyOf(alias.getClassName());
            String pkg = key == null ? "" : app(this, key);
            if (!pkg.isEmpty()) {
                Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } catch (ActivityNotFoundException | SecurityException ignored) {
        } finally {
            finish();
        }
    }

    /** Philips (TP Vision) firmware sends these actions; elsewhere the rows are not offered. */
    static boolean offered() {
        return Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT).contains("philips")
                || Build.BRAND.toLowerCase(java.util.Locale.ROOT).contains("philips");
    }

    static String app(Context context, String key) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getString(PREFIX + key, "");
    }

    /** The button opens `pkg`; empty switches its alias off, back to the TV's own handling. */
    static void set(Context context, String key, String pkg) {
        context.getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit().putString(PREFIX + key, pkg).apply();
        for (String[] button : BUTTONS) {
            if (!button[0].equals(key)) continue;
            context.getPackageManager().setComponentEnabledSetting(
                    new ComponentName(context.getPackageName(), context.getPackageName() + button[1]),
                    pkg.isEmpty() ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        }
    }

    private static String keyOf(String className) {
        for (String[] button : BUTTONS) if (className.endsWith(button[1])) return button[0];
        return null;
    }
}
