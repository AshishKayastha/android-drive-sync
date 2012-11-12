
package com.example.android.cloudnotes.service;

import android.accounts.AccountManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.os.Process;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

import com.example.android.cloudnotes.provider.NotesProvider;
import com.example.android.cloudnotes.ui.HomeActivity;
import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.api.client.extensions.android2.AndroidHttp;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpExecuteInterceptor;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.json.jackson.JacksonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;

public class DriveSyncService extends Service {

    private static final String OAUTH_SCOPE_PREFIX = "oauth2:";
    private static final String NOTE_MIME_TYPE = "text/plain";

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
                    stopSelf();
                } else {
                    final String accessToken = getAccessToken(syncAccountName);
                    if (!TextUtils.isEmpty(accessToken)) {
                        syncNotes(syncAccountName, accessToken);
                        
                        // signal that syncing completed
                        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(
                                new Intent(HomeActivity.LB_SYNC_COMPLETE));
                        stopSelf();
                    }
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
            stopSelf();
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

    private void syncNotes(final String syncAccountName, final String accessToken) {
        final Drive drive = getDriveService(syncAccountName, accessToken);
        ContentResolver cr = getContentResolver();
        try {
            // loop over saved files and add any new notes to drive
            Cursor savedNotes = cr.query(NotesProvider.CONTENT_URI, NotesSyncQuery.PROJECTION,
                    null, null, null);
            if (savedNotes.moveToFirst()) {
                do {
                    final String driveId = savedNotes.getString(NotesSyncQuery.DRIVE_ID);
                    if (TextUtils.isEmpty(driveId)) {
                        // exists locally but not in drive Ð upload it
                        File newNote = new File();
                        newNote.setTitle(savedNotes.getString(NotesSyncQuery.TITLE));
                        newNote.setMimeType(NOTE_MIME_TYPE);

                        File inserted = drive
                                .files()
                                .insert(newNote,
                                        ByteArrayContent.fromString(NOTE_MIME_TYPE,
                                                savedNotes.getString(NotesSyncQuery.BODY)))
                                .execute();

                        // save the drive id to the db
                        ContentValues cv = new ContentValues();
                        cv.put(NotesProvider.KEY_DRIVE_ID, inserted.getId());
                        Uri noteUri = ContentUris.withAppendedId(NotesProvider.CONTENT_URI,
                                savedNotes.getLong(NotesSyncQuery.ID));
                        cr.update(noteUri, cv, null, null);
                    } else {
                        // TODO compare timestamps etc.
                    }
                } while (savedNotes.moveToNext());
            }

            // loop over all files in drive and see if any need to be
            // synced locally
            FileList driveFilesList = drive.files().list().execute();
            for (File remote : driveFilesList.getItems()) {
                if (remote.getLabels().getTrashed()) {
                    // skip deleted files
                    continue;
                }
                final String where = NotesProvider.KEY_DRIVE_ID + "=?";
                final String[] arguments = new String[] {
                    remote.getId()
                };
                Cursor c = cr.query(NotesProvider.CONTENT_URI, NotesDownloadQuery.PROJECTION,
                        where, arguments, null);
                if (c.getCount() == 0) {
                    // exists in drive but not locally Ð download it
                    final String title = remote.getTitle();
                    final String body = getFileContents(drive, remote.getDownloadUrl());
                    final ContentValues cv = new ContentValues();
                    cv.put(NotesProvider.KEY_TITLE, title);
                    cv.put(NotesProvider.KEY_BODY, body);
                    cv.put(NotesProvider.KEY_DRIVE_ID, remote.getId());
                    cv.put(NotesProvider.KEY_LAST_MODIFIED, remote.getModifiedDate().getValue());
                    cr.insert(NotesProvider.CONTENT_URI, cv);
                } else {
                    // TODO compare timestamps etc.
                }
            }

        } catch (IOException e) {
            // FIXME error handling
            Log.e(getClass().getSimpleName(), "Drive esplode", e);
        }
    }

    private String getFileContents(Drive drive, String downloadUrl) {
        if (!TextUtils.isEmpty(downloadUrl)) {
            try {
                HttpResponse resp = drive.getRequestFactory()
                        .buildGetRequest(new GenericUrl(downloadUrl)).execute();

                final char[] buffer = new char[1024];
                final StringBuilder out = new StringBuilder();
                Reader in = null;
                try {
                    in = new InputStreamReader(resp.getContent(), "UTF-8");
                    for (;;) {
                        int rsz = in.read(buffer, 0, buffer.length);
                        if (rsz < 0)
                            break;
                        out.append(buffer, 0, rsz);
                    }
                } finally {
                    in.close();
                }
                return out.toString();
            } catch (IOException e) {
                Log.e(getClass().getSimpleName(), "Error downloading file contenst", e);
            }
        }
        return null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private interface NotesSyncQuery {

        final static String[] PROJECTION = {
                NotesProvider.KEY_ID, NotesProvider.KEY_TITLE, NotesProvider.KEY_BODY,
                NotesProvider.KEY_DRIVE_ID, NotesProvider.KEY_LAST_MODIFIED
        };

        final static int ID = 0;
        final static int TITLE = 1;
        final static int BODY = 2;
        final static int DRIVE_ID = 3;
        final static int LAST_MODIFIED = 4;
    }

    private interface NotesDownloadQuery {

        final static String[] PROJECTION = {
            NotesProvider.KEY_ID
        };

        final static int ID = 0;
    }

}
