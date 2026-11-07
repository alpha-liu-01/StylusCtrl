package app.stylusctrl;

import android.app.Application;
import android.os.Build;

import com.google.android.material.color.DynamicColors;
import com.topjohnwu.superuser.Shell;

public class App extends Application {
    static {
        Shell.enableVerboseLogging = false;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(15));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 33) {
            DynamicColors.applyToActivitiesIfAvailable(this);
        }
    }
}
