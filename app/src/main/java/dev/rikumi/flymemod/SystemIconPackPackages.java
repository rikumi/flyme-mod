package dev.rikumi.flymemod;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** System package events restart the owner process if necessary; rebuild always occurs off the receiver thread. */
public final class SystemIconPackPackages extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) { SystemIconPackStore.get(context).rebuild(); }
}
