package dev.kano.kclipsync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the service on boot or package replacement when the user left it enabled. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Settings.enabled(context)) {
            context.startForegroundService(new Intent(context, SyncService.class));
        }
    }
}
