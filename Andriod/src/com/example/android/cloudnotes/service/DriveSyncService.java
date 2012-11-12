
package com.example.android.cloudnotes.service;

import android.accounts.AccountManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.Process;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;

import com.example.android.cloudnotes.ui.HomeActivity;

public class DriveSyncService extends Service {

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Thread t = new Thread("CloudNotes Sync") {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                SharedPreferences prefs = getSharedPreferences(HomeActivity.KEY_PREFS, MODE_PRIVATE);
                final String syncAccountName = prefs.getString(AccountManager.KEY_ACCOUNT_NAME,
                        null);
                if (TextUtils.isEmpty(syncAccountName)) {
                    LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(
                            new Intent(HomeActivity.LB_REQUEST_ACCOUNT));
                } else {
                    // TODO
                }
            }
        };
        t.start();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}
