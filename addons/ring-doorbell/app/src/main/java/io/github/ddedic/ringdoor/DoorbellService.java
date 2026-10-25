package io.github.ddedic.ringdoor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Holds the TV's one connection to the bridge Worker, which relays Ring's webhooks. A foreground service, so
 * Android keeps it; it sleeps on the socket between events. When the screen comes back on it reconnects at once
 * and asks for what it missed. All calls to Ring (tokens, linking, pictures) run here, on the home connection.
 */
public final class DoorbellService extends Service {
    static final String TAG = "RingDoor";
    private static final String CHANNEL = "doorbell";
    /** An event older than this when it arrives (the TV was off) goes in the list without a pop-up. */
    private static final long LATE_MS = 90_000;
    /** Ring's refresh token lasts about 30 days; refreshing at least daily keeps it alive while the TV is used. */
    private static final long REFRESH_EVERY_MS = 24 * 3_600_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private volatile Bridge bridge;
    private Thread worker;
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            Bridge b = bridge;
            Store store = new Store(context);
            if (b != null && !store.connected()) b.close();   // wakes the loop's back-off into a reconnect
        }
    };

    static void start(Context context) {
        Store store = new Store(context);
        store.importCredentials();
        if (!store.configured() || !store.enabled()) return;
        try {
            context.startForegroundService(new Intent(context, DoorbellService.class));
        } catch (RuntimeException notAllowedNow) {
            Log.w(TAG, "service start refused: " + notAllowedNow.getClass().getSimpleName());
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, DoorbellService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.channel),
                NotificationManager.IMPORTANCE_MIN));
        startForeground(1, new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_door)
                .setContentTitle(getString(R.string.listening)).build());
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (worker == null || !worker.isAlive()) {
            running = true;
            worker = new Thread(this::loop, "ring-bridge");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        Bridge b = bridge;
        if (b != null) b.close();
        unregisterReceiver(screenOn);
        new Store(this).setConnected(false, "");
        notifyStatus();
        work.shutdown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void loop() {
        Store store = new Store(this);
        int failures = 0;
        while (running) {
            Bridge b = new Bridge();
            bridge = b;
            try {
                store.importCredentials();
                if (!store.configured() || !store.enabled()) { stopSelf(); return; }
                b.run(store.bridgeUrl().replaceAll("/+$", "") + "/tv", store.tvToken(), new Bridge.Listener() {
                    @Override public void onOpen(Bridge open) {
                        store.setConnected(true, "");
                        notifyStatus();
                        try {
                            open.send(new JSONObject().put("t", "hello").put("since", store.lastEvent()).toString());
                        } catch (Exception ignored) { /* the replay is a nicety */ }
                        if (store.linked()) work.execute(() -> housekeeping(store));
                        Log.i(TAG, "bridge connected");
                    }

                    @Override public void onText(Bridge open, String text) {
                        handle(store, open, text);
                    }
                });
                failures = 0;
            } catch (Exception failed) {
                failures++;
                store.setConnected(false, failed.getClass().getSimpleName() + ": " + failed.getMessage());
                notifyStatus();
                Log.w(TAG, "bridge: " + failed.getClass().getSimpleName() + ": " + failed.getMessage());
            }
            store.setConnected(false, store.lastError());
            if (!running) return;
            SystemClock.sleep(Math.min(60, 3L * Math.max(1, failures)) * 1000L);
        }
    }

    private void handle(Store store, Bridge open, String text) {
        JSONObject m;
        try {
            m = new JSONObject(text);
        } catch (JSONException broken) {
            return;
        }
        String t = m.optString("t");
        if ("event".equals(t)) {
            work.execute(() -> event(store, m.optString("body"), m.optLong("at")));
        } else if ("code".equals(t) || "link".equals(t)) {
            // Ring's linking steps: answer within the Worker's 50 s, from here, so Ring sees the home IP.
            work.execute(() -> answer(store, open, m));
        }
    }

    private void answer(Store store, Bridge open, JSONObject m) {
        String id = m.optString("id");
        String error = "";
        try {
            RingApi api = store.api();
            if ("code".equals(m.optString("t"))) {
                RingApi.Tokens tokens = api.exchange(m.getString("code"));
                store.setTokens(tokens);
                store.setAccountId(api.accountId(tokens.access));
                Log.i(TAG, "Ring tokens received");
            } else {
                String expected = RingApi.nonce(store.hmacKey(), m.getString("time"), store.accountId());
                if (!java.security.MessageDigest.isEqual(expected.getBytes(), m.getString("nonce").getBytes())) {
                    throw new IllegalStateException("the link is for another Ring account");
                }
                api.completeLink(token(store), m.getString("nonce"), getString(R.string.account_identifier));
                store.markLinked();
                housekeeping(store);
                Log.i(TAG, "Ring account linked");
            }
        } catch (Exception failed) {
            error = failed.getClass().getSimpleName() + ": " + failed.getMessage();
            Log.w(TAG, "link step: " + error);
        }
        try {
            open.send(new JSONObject().put("t", "answer").put("id", id).put("ok", error.isEmpty())
                    .put("error", error).toString());
        } catch (Exception ignored) { /* the Worker times out and tells Ring it failed */ }
        notifyStatus();
    }

    /** Devices (names, the chime) at most hourly, and the daily token refresh that keeps the link alive. */
    private void housekeeping(Store store) {
        try {
            if (System.currentTimeMillis() - store.refreshedAt() > REFRESH_EVERY_MS) refresh(store);
            if (System.currentTimeMillis() - store.devicesAt() > 3_600_000L || store.camera() == null) {
                refreshDevices(store);
            }
        } catch (Exception failed) {
            Log.w(TAG, "housekeeping: " + failed.getClass().getSimpleName() + ": " + failed.getMessage());
        }
        notifyStatus();
    }

    /** Ring's device list; logs only counts, never names or ids. */
    static void refreshDevices(Store store) throws Exception {
        java.util.List<RingApi.Device> devices = store.api().devices(token(store));
        store.setDevices(devices);
        int chimes = 0;
        for (RingApi.Device d : devices) if (d.chime) chimes++;
        Log.i(TAG, "devices: " + (devices.size() - chimes) + " cameras, " + chimes + " chimes");
    }

    private void event(Store store, String body, long relayedAt) {
        RingApi.Event e = RingApi.parseWebhook(body);
        if (e == null) return;
        boolean late = System.currentTimeMillis() - Math.max(e.at, relayedAt) > LATE_MS;
        Store.Event event = new Store.Event(e.id, e.kind, e.deviceId, store.deviceName(e.deviceId), e.subType, e.at, late);
        if (!e.ding() && Store.MOTION_OFF.equals(store.motion())) {
            store.addEvent(event);   // kept in the list, nothing on screen
            notifyStatus();
            return;
        }
        if (!store.addEvent(event)) return;
        notifyStatus();
        Log.i(TAG, "event " + e.kind + (late ? " (missed)" : ""));
        boolean show = !late && store.alerts(event);
        if (show) {
            main.post(() -> {
                if (e.ding() && store.pause()) pausePlayback();
                DoorOverlay.show(this, event, store.popupSeconds());
            });
        }
        try {
            byte[] jpeg = store.api().snapshot(token(store), e.deviceId, e.at, store.linkedAt());
            store.write(store.snapshotFile(e.id), jpeg);
            store.write(store.latestFile(), jpeg);
            notifyStatus();
            if (show) main.post(() -> DoorOverlay.picture(e.id, store.snapshotFile(e.id)));
        } catch (Exception failed) {
            Log.w(TAG, "snapshot: " + failed.getClass().getSimpleName() + ": " + failed.getMessage());
        }
    }

    private void pausePlayback() {
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio == null || !audio.isMusicActive()) return;
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
    }

    private void notifyStatus() {
        getContentResolver().notifyChange(StatusProvider.STATUS, null);
    }

    /** A valid access token, refreshed a minute before it runs out. Shared by every screen and thread. */
    static synchronized String token(Store store) throws Exception {
        if (!store.linked()) throw new IllegalStateException("Ring is not linked yet");
        if (!store.accessToken().isEmpty() && System.currentTimeMillis() < store.expiresAt() - 60_000) {
            return store.accessToken();
        }
        return refresh(store);
    }

    private static synchronized String refresh(Store store) throws Exception {
        RingApi.Tokens tokens = store.api().refresh(store.refreshToken());
        store.setTokens(tokens);
        return tokens.access;
    }
}
