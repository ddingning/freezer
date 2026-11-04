package com.frostbyte.freezer.adb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

import com.frostbyte.freezer.MainActivity;
import com.frostbyte.freezer.R;
import com.frostbyte.freezer.adb.mdns.AdbMdns;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreground pairing helper: discovers the local ADB TLS pairing service and exposes an inline
 * notification reply for the six-digit code. The system pairing dialog does not need to be left
 * for the app to receive the code.
 */
public final class AdbPairingService extends Service {
    private static final String TAG = "AdbPairingService";
    public static final String CHANNEL_ID = "adb_pairing";
    public static final String DEVICE_NAME = AdbConnectionManager.DEVICE_NAME;

    private static final int ID_FOREGROUND = 1;
    private static final int ID_RESULT = 2;
    private static final String REPLY_KEY = "pairing_code";
    private static final String EXTRA_PORT = "port";

    private static final int REQ_CONTENT = 1;
    private static final int REQ_STOP = 2;
    private static final int REQ_RETRY = 3;
    private static final int REQ_REPLY_BASE = 1000;

    private static final String ACTION_START = "com.frostbyte.freezer.action.PAIRING_START";
    private static final String ACTION_STOP = "com.frostbyte.freezer.action.PAIRING_STOP";
    private static final String ACTION_REPLY = "com.frostbyte.freezer.action.PAIRING_REPLY";
    private static final String ACTION_RETRY = "com.frostbyte.freezer.action.PAIRING_RETRY";

    private static volatile boolean running = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private NotificationManager notificationManager;
    private AdbMdns adbMdns;
    private boolean searching;
    private boolean pairing;
    private int lastPort = -1;
    private String lastHost;

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, AdbPairingService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, AdbPairingService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        switch (intent.getAction()) {
            case ACTION_START:
                startDiscovery();
                goForeground(waitingNotification());
                return START_REDELIVER_INTENT;
            case ACTION_REPLY:
                handleReply(intent);
                return START_REDELIVER_INTENT;
            case ACTION_RETRY:
                goForeground(inputOrWaitingNotification());
                return START_REDELIVER_INTENT;
            case ACTION_STOP:
                stopDiscovery();
                NotificationManagerCompat.from(this).cancel(ID_FOREGROUND);
                NotificationManagerCompat.from(this).cancel(ID_RESULT);
                stopForegroundSafe();
                stopSelf();
                return START_NOT_STICKY;
            default:
                stopSelf();
                return START_NOT_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        stopDiscovery();
        try {
            NotificationManagerCompat.from(this).cancel(ID_FOREGROUND);
        } catch (Throwable ignored) {
        }
        stopForegroundSafe();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startDiscovery() {
        if (searching) {
            return;
        }
        searching = true;
        adbMdns = new AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING, (hostAddress, port) -> {
            Log.i(TAG, "pairing service resolved: " + hostAddress + ":" + port);
            if (port <= 0) {
                lastPort = -1;
                return;
            }
            lastPort = port;
            lastHost = hostAddress == null ? null : hostAddress.getHostAddress();
            if (!pairing) {
                notifyNotification(ID_FOREGROUND, inputNotification(port));
            }
        });
        adbMdns.start();
    }

    private void stopDiscovery() {
        if (!searching) {
            return;
        }
        searching = false;
        if (adbMdns != null) {
            adbMdns.stop();
            adbMdns = null;
        }
    }

    private void handleReply(Intent intent) {
        CharSequence codeText = null;
        try {
            android.os.Bundle results = RemoteInput.getResultsFromIntent(intent);
            if (results != null) {
                codeText = results.getCharSequence(REPLY_KEY);
            }
        } catch (Throwable ignored) {
        }
        final String code = codeText == null ? "" : codeText.toString().trim();
        final int port = intent.getIntExtra(EXTRA_PORT, lastPort);
        if (code.isEmpty()) {
            showFailure("未输入配对码");
            goForeground(inputOrWaitingNotification());
            return;
        }
        if (port <= 0) {
            showFailure("无法确定配对端口，请重新打开配对对话框");
            goForeground(waitingNotification());
            return;
        }
        goForeground(workingNotification(port));
        pair(code, port);
    }

    private void pair(final String code, final int port) {
        pairing = true;
        new Thread(() -> {
            Throwable failure = null;
            List<String> hosts = new ArrayList<>();
            if (lastHost != null && !lastHost.isEmpty()) {
                hosts.add(lastHost);
            }
            if (!hosts.contains("127.0.0.1")) {
                hosts.add("127.0.0.1");
            }
            for (String host : hosts) {
                try {
                    AdbConnectionManager manager = AdbConnectionManager.get(getApplicationContext());
                    manager.pair(host, port, code);
                    failure = null;
                    break;
                } catch (Throwable e) {
                    failure = e;
                    if (!(e instanceof ConnectException)) {
                        break;
                    }
                }
            }

            final Throwable result = failure;
            mainHandler.post(() -> {
                pairing = false;
                if (result == null) {
                    new PairingState(getApplicationContext()).markPaired();
                    notifyNotification(ID_RESULT, successNotification());
                    stopDiscovery();
                    try {
                        NotificationManagerCompat.from(this).cancel(ID_FOREGROUND);
                    } catch (Throwable ignored) {
                    }
                    stopForegroundSafe();
                    stopSelf();
                } else {
                    showFailure(PairingErrors.describe(result));
                    goForeground(inputOrWaitingNotification());
                }
            });
        }, "adb-pairing").start();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "无线调试配对",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("通过通知栏输入六位配对码");
        channel.setSound(null, null);
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification.Builder builder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID);
        }
        return new Notification.Builder(this);
    }

    private Notification.Builder base(String title) {
        return builder()
                .setSmallIcon(R.drawable.ic_snowflake)
                .setContentTitle(title)
                .setContentIntent(contentIntent());
    }

    private PendingIntent contentIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, REQ_CONTENT, intent, flags);
    }

    private PendingIntent serviceIntent(int requestCode, Intent intent, boolean mutable) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= mutable ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(this, requestCode, intent, flags);
        }
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    private PendingIntent replyIntent(int port) {
        return serviceIntent(REQ_REPLY_BASE + port,
                new Intent(this, AdbPairingService.class)
                        .setAction(ACTION_REPLY)
                        .putExtra(EXTRA_PORT, port),
                true);
    }

    private PendingIntent stopIntent() {
        return serviceIntent(REQ_STOP,
                new Intent(this, AdbPairingService.class).setAction(ACTION_STOP), false);
    }

    private PendingIntent retryIntent() {
        return serviceIntent(REQ_RETRY,
                new Intent(this, AdbPairingService.class).setAction(ACTION_RETRY), false);
    }

    private Notification.Action stopAction() {
        return new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_snowflake),
                "停止",
                stopIntent()).build();
    }

    private Notification waitingNotification() {
        return base("等待配对请求")
                .setContentText("在开发者选项里打开“无线调试 → 使用配对码配对设备”")
                .setOngoing(true)
                .addAction(stopAction())
                .build();
    }

    private Notification inputNotification(int port) {
        RemoteInput remoteInput = new RemoteInput.Builder(REPLY_KEY)
                .setLabel("六位配对码")
                .build();
        Notification.Action action = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_snowflake),
                "输入配对码",
                replyIntent(port))
                .addRemoteInput(remoteInput)
                .build();
        return base("发现配对请求")
                .setContentText("下拉通知栏，点按输入 6 位配对码")
                .setOngoing(true)
                .addAction(action)
                .addAction(stopAction())
                .build();
    }

    private Notification workingNotification(int port) {
        return base("配对中…")
                .setContentText("端口 " + port)
                .setOngoing(true)
                .build();
    }

    private Notification successNotification() {
        return base("配对成功")
                .setContentText("密钥已写入 adbd，请返回应用连接")
                .setAutoCancel(true)
                .build();
    }

    private Notification failureNotification(String reason) {
        return base("配对失败")
                .setContentText(reason)
                .setStyle(new Notification.BigTextStyle().bigText(reason))
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_snowflake),
                        "重试",
                        retryIntent()).build())
                .addAction(stopAction())
                .build();
    }

    private Notification inputOrWaitingNotification() {
        return lastPort > 0 ? inputNotification(lastPort) : waitingNotification();
    }

    private void showFailure(String reason) {
        notifyNotification(ID_RESULT, failureNotification(reason));
    }

    private void notifyNotification(int id, Notification notification) {
        try {
            NotificationManagerCompat.from(this).notify(id, notification);
        } catch (SecurityException e) {
            Log.w(TAG, "cannot post notification", e);
        }
    }

    private void goForeground(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(ID_FOREGROUND, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(ID_FOREGROUND, notification);
            }
        } catch (Throwable e) {
            Log.e(TAG, "startForeground failed", e);
            notifyNotification(ID_FOREGROUND, notification);
        }
    }

    private void stopForegroundSafe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }
}
