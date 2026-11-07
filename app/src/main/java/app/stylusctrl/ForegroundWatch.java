package app.stylusctrl;

import android.app.Notification;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Writes the foreground package beside config.json. The daemon reads that file once a second. */
public class ForegroundWatch extends Service {
    private static final int NOTE_ID = 7;
    private static final long INTERVAL_MS = 2000;
    private static final String CHANNEL = "stylusctrl.watch";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "stylusctrl-watch");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static final long REPLAY_MS = 20_000L;
    private static final long INITIAL_MS = 180_000L;

    private String shown = "";
    private String home = "";
    private String remembered = "";
    private final ArrayDeque<Mark> marks = new ArrayDeque<>();
    private volatile boolean running;

    /** Folded foreground at one moment, so a later poll can replay across it. */
    private static final class Mark {
        final long time;
        final String current;
        final boolean alive;
        final boolean launcherUp;

        Mark(long time, String current, boolean alive, boolean launcherUp) {
            this.time = time;
            this.current = current == null ? "" : current;
            this.alive = alive;
            this.launcherUp = launcherUp;
        }
    }

    private final Runnable tick = () -> io.execute(this::poll);

    private void poll() {
        if (!running) return;
        if (!usageGranted()) {
            handler.post(this::stopSelf);
            return;
        }
        String pkg = latestPackage();
        RootStore.writeForeground(ForegroundWatch.this, pkg);
        if (!running) return;
        handler.post(() -> {
            if (!running) return;
            if (!pkg.equals(shown)) {
                shown = pkg;
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.notify(NOTE_ID, buildNote(pkg));
            }
            handler.postDelayed(tick, INTERVAL_MS);
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.watch_channel),
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
        Notification note = buildNote("");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTE_ID, note);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(tick);
        if (!usageGranted()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        handler.post(tick);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(tick);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNote(String pkg) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        String text = pkg == null || pkg.isEmpty() ? getString(R.string.watch_note) : pkg;
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle(getString(R.string.watch_channel))
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    @SuppressWarnings("deprecation")
    static boolean allowed(android.content.Context context) {
        if (Build.VERSION.SDK_INT < 29) return false;
        android.app.AppOpsManager ops = (android.app.AppOpsManager) context.getSystemService(android.content.Context.APP_OPS_SERVICE);
        if (ops == null) return false;
        int mode = ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(),
                context.getPackageName());
        return mode == android.app.AppOpsManager.MODE_ALLOWED;
    }

    private boolean usageGranted() {
        return allowed(this);
    }

    /**
     * Drawing app that should own the pen preset.
     * Opening recents resumes the launcher and then drops it again, often without a new
     * resume for the app underneath. That blip must not clear the app. The home screen does:
     * the launcher stays up and the previous app is stopped.
     */
    @SuppressWarnings("deprecation")
    private String latestPackage() {
        UsageStatsManager stats = getSystemService(UsageStatsManager.class);
        if (stats == null) return remembered;
        long now = System.currentTimeMillis();
        Mark base = checkpoint(now - REPLAY_MS);
        long from = base == null ? now - INITIAL_MS : base.time + 1;
        String current = base == null ? "" : base.current;
        boolean alive = base != null && base.alive;
        boolean launcherUp = base != null && base.launcherUp;
        UsageEvents events = stats.queryEvents(from, now + 1);
        if (events != null) {
            UsageEvents.Event event = new UsageEvents.Event();
            String launcher = homePackage();
            String self = getPackageName();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                String pkg = event.getPackageName();
                if (pkg == null) continue;
                int type = event.getEventType();
                boolean resume = type == UsageEvents.Event.ACTIVITY_RESUMED
                        || type == UsageEvents.Event.MOVE_TO_FOREGROUND;
                boolean away = type == UsageEvents.Event.ACTIVITY_PAUSED
                        || type == UsageEvents.Event.MOVE_TO_BACKGROUND
                        || type == UsageEvents.Event.ACTIVITY_STOPPED;
                if (!resume && !away) continue;
                if (pkg.equals(launcher)) {
                    launcherUp = resume;
                    if (resume && !alive) current = "";
                    continue;
                }
                if (pkg.equals(self) || "com.android.systemui".equals(pkg)) {
                    if (resume) launcherUp = false;
                    continue;
                }
                if (resume) {
                    current = pkg;
                    alive = true;
                    launcherUp = false;
                } else if (type == UsageEvents.Event.ACTIVITY_STOPPED && pkg.equals(current)) {
                    alive = false;
                    if (launcherUp) current = "";
                }
            }
        }
        marks.addLast(new Mark(now, current, alive, launcherUp));
        long keep = now - REPLAY_MS;
        while (marks.size() > 1 && marks.peekFirst().time < keep) marks.pollFirst();
        remembered = current;
        return current;
    }

    /** Newest saved fold at or before boundary, so replay stays in order. */
    private Mark checkpoint(long boundary) {
        Mark chosen = null;
        for (Mark mark : marks) {
            if (mark.time > boundary) break;
            chosen = mark;
        }
        return chosen;
    }

    private String homePackage() {
        if (!home.isEmpty()) return home;
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (info == null || info.activityInfo == null) return "";
        home = info.activityInfo.packageName == null ? "" : info.activityInfo.packageName;
        return home;
    }
}
