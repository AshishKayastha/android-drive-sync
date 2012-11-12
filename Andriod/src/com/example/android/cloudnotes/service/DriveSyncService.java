
package com.example.android.cloudnotes.service;

import android.accounts.AccountManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.Process;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

import com.example.android.cloudnotes.ui.HomeActivity;
import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.api.client.extensions.android2.AndroidHttp;
import com.google.api.client.http.HttpExecuteInterceptor;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.jackson.JacksonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;

import java.io.IOException;

public class DriveSyncService extends Service {

    private static final String OAUTH_SCOPE_PREFIX = "oauth2:";

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
                    final String accessToken = getAccessToken(syncAccountName);
                }
            }
        };
        t.start();
        return START_STICKY;
    }

    private String getAccessToken(final String syncAccount) {
        try {
            return GoogleAuthUtil.getToken(getApplicationContext(), syncAccount, OAUTH_SCOPE_PREFIX
                    + DriveScopes.DRIVE_FILE);
        } catch (UserRecoverableAuthException e) {
            Intent authRequiredIntent = new Intent(HomeActivity.LB_AUTH_APP);
            authRequiredIntent.putExtra(HomeActivity.EXTRA_AUTH_APP_INTENT, e.getIntent());
            LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(
                    authRequiredIntent);
        } catch (IOException e) {
            // FIXME do exponential backoff
        } catch (GoogleAuthException e) {
            Log.e(getClass().getSimpleName(), "Fatal authorization exception", e);
        }
        return null;
    }

    private Drive getDriveService(final String syncAccountName, final String accessToken) {
        return new Drive.Builder(AndroidHttp.newCompatibleTransport(), new JacksonFactory(),
                new HttpRequestInitializer() {
                    @Override
                    public void initialize(HttpRequest httpRequest) throws IOException {
                        httpRequest.setInterceptor(new HttpExecuteInterceptor() {
                            @Override
                            public void intercept(HttpRequest request) throws IOException {
                                request.getHeaders().setAuthorization("Bearer " + accessToken);
                            }
                        });
                    }
                }).build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}
