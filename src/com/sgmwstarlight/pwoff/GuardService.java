package com.sgmwstarlight.pwoff;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;

/**
 * Turns the pedestrian warning (AVAS) off after every head-unit boot / wake-up.
 *
 * The SGMW vehicle settings app exports an unprotected AIDL service
 * (com.sgmw.vehiclesettings.IVechicleSettingsInterface); we call it exactly like
 * its own UI switch does:
 *   setPedestrianWarning: 1 = on, 2 = off
 *   getPedestrianWarning: 0 = off, 1 = on, anything else = unavailable
 *
 * The head unit deep-sleeps instead of rebooting, so besides BOOT_COMPLETED we
 * also arm on resume (detected via an uptime/elapsed-realtime gap) and on screen-on
 * after the screen has been off for a while. While armed we poll and switch the
 * warning off whenever it reads "on". Once it has stayed off for SETTLE_MS we
 * disarm, so turning it back on manually during the drive is respected.
 */
public class GuardService extends Service {
    static final String TAG = "PwOff";

    private static final String VS_PACKAGE = "com.sgmw.vehiclesettings";
    private static final String VS_ACTION = "com.sgmw.vehiclesettings.USERSERVICE";
    private static final String VS_DESCRIPTOR = "com.sgmw.vehiclesettings.IVechicleSettingsInterface";
    private static final int TX_GET_PEDESTRIAN_WARNING = 10;
    private static final int TX_SET_PEDESTRIAN_WARNING = 16;
    private static final int STATUS_ON = 1;
    private static final int STATUS_OFF = 0;
    private static final int CMD_OFF = 2;

    private static final long POLL_ARMED_MS = 2_000;
    private static final long POLL_IDLE_MS = 10_000;
    private static final long RESEND_MS = 3_000;
    private static final long SETTLE_MS = 60_000;
    private static final long ARM_TIMEOUT_MS = 20 * 60_000;
    private static final long SLEEP_GAP_MS = 5_000;
    private static final long SCREEN_OFF_REARM_MS = 60_000;

    private static final String CHANNEL_ID = "guard";
    private static final String EXTRA_REASON = "reason";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private IBinder remote;
    private boolean bound;

    private boolean armed;
    private long armedAt;
    private long offSince = -1;
    private long lastSendAt = -RESEND_MS;
    private int sends;

    private long lastTickUptime = -1;
    private long lastTickElapsed = -1;
    private long screenOffAt = -1;

    static void start(Context context, String reason) {
        Intent i = new Intent(context, GuardService.class).putExtra(EXTRA_REASON, reason);
        context.startForegroundService(i);
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "vehicle settings service connected");
            remote = service;
            if (armed) {
                handler.removeCallbacks(tick);
                handler.post(tick);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "vehicle settings service disconnected");
            remote = null;
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.w(TAG, "vehicle settings binding died, rebinding");
            remote = null;
            unbind();
            bind();
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long now = SystemClock.elapsedRealtime();
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                screenOffAt = now;
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                if (screenOffAt >= 0 && now - screenOffAt >= SCREEN_OFF_REARM_MS) {
                    arm("screen on after " + (now - screenOffAt) / 1000 + "s off");
                }
                screenOffAt = -1;
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            long up = SystemClock.uptimeMillis();
            long el = SystemClock.elapsedRealtime();
            if (lastTickElapsed >= 0) {
                long slept = (el - lastTickElapsed) - (up - lastTickUptime);
                if (slept > SLEEP_GAP_MS) {
                    arm("resumed after " + slept / 1000 + "s sleep");
                }
            }
            lastTickUptime = up;
            lastTickElapsed = el;

            if (armed) {
                check(el);
            }
            handler.postDelayed(this, armed ? POLL_ARMED_MS : POLL_IDLE_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "后台守护", NotificationManager.IMPORTANCE_MIN);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);

        bind();
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
                .setContentTitle("行人警示自动关闭")
                .setContentText("开机/唤醒后自动关闭行人警示")
                .setOngoing(true)
                .build();
        startForeground(1, n);

        String reason = intent != null ? intent.getStringExtra(EXTRA_REASON) : null;
        arm(reason != null ? reason : "restart");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(screenReceiver);
        unbind();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void arm(String reason) {
        Log.i(TAG, "armed: " + reason);
        armed = true;
        armedAt = SystemClock.elapsedRealtime();
        offSince = -1;
        lastSendAt = -RESEND_MS;
        sends = 0;
        if (remote == null) {
            bind();
        }
        handler.post(tick);
    }

    private void disarm(String reason) {
        Log.i(TAG, "disarmed: " + reason);
        armed = false;
    }

    private void check(long now) {
        if (remote == null) {
            bind();
            if (now - armedAt >= ARM_TIMEOUT_MS) {
                disarm("service unavailable for " + ARM_TIMEOUT_MS / 60_000 + " min");
            }
            return;
        }
        int status = getPedestrianWarning();
        if (status == STATUS_ON) {
            offSince = -1;
            if (now - lastSendAt >= RESEND_MS) {
                sends++;
                Log.i(TAG, "pedestrian warning is ON, sending OFF (attempt " + sends + ")");
                setPedestrianWarning(CMD_OFF);
                lastSendAt = now;
            }
        } else if (status == STATUS_OFF && sends > 0) {
            if (offSince < 0) {
                offSince = now;
                Log.i(TAG, "pedestrian warning is OFF");
            } else if (now - offSince >= SETTLE_MS) {
                disarm("stayed off for " + SETTLE_MS / 1000 + "s");
            }
        } else if (now - armedAt >= ARM_TIMEOUT_MS) {
            disarm("never saw it on within " + ARM_TIMEOUT_MS / 60_000 + " min (last status " + status + ")");
        }
    }

    private int getPedestrianWarning() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VS_DESCRIPTOR);
            remote.transact(TX_GET_PEDESTRIAN_WARNING, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Exception e) {
            Log.w(TAG, "getPedestrianWarning failed", e);
            return -1;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void setPedestrianWarning(int value) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VS_DESCRIPTOR);
            data.writeInt(value);
            remote.transact(TX_SET_PEDESTRIAN_WARNING, data, reply, 0);
            reply.readException();
        } catch (Exception e) {
            Log.w(TAG, "setPedestrianWarning failed", e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void bind() {
        if (bound) {
            return;
        }
        Intent i = new Intent(VS_ACTION).setPackage(VS_PACKAGE);
        try {
            bound = bindService(i, connection, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bindService -> " + bound);
        } catch (Exception e) {
            Log.w(TAG, "bindService failed", e);
        }
    }

    private void unbind() {
        if (bound) {
            try {
                unbindService(connection);
            } catch (Exception ignored) {
            }
            bound = false;
        }
    }
}
