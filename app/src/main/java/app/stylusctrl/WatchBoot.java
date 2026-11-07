package app.stylusctrl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Starts the foreground watcher after boot once usage access is already allowed. */
public class WatchBoot extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || !ForegroundWatch.allowed(context)) return;
        Intent service = new Intent(context, ForegroundWatch.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
        else context.startService(service);
    }
}
