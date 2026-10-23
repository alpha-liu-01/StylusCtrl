package app.stylusctrl;

import android.app.Application;

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
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
