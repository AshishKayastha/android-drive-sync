/*
 * Copyright (C) 2012 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.example.android.cloudnotes.provider;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

public class NotesProvider extends ContentProvider {

    public static final String CONTENT_AUTHORITY = "com.example.android.cloudnotes";

    public static final Uri BASE_CONTENT_URI = Uri.parse("content://" + CONTENT_AUTHORITY);

    public static final Uri CONTENT_URI = BASE_CONTENT_URI.buildUpon().appendPath("notes").build();

    public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.cloudnotes.notes";

    private static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.cloudnotes.note";

    // The underlying database
    private SQLiteDatabase notesDB;

    // Create the constants used to differentiate between the different URI
    // requests
    private static final int ALL_NOTES = 1;
    private static final int NOTE_ID = 2;

    private static final UriMatcher uriMatcher;

    // Allocate the UriMatcher object, where a URI ending in 'notes' will
    // correspond to a request for all notes, and 'notes' with a trailing
    // '/[rowID]' will represent a single note row.
    static {
        uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        uriMatcher.addURI(CONTENT_AUTHORITY, "notes", ALL_NOTES);
        uriMatcher.addURI(CONTENT_AUTHORITY, "notes/#", NOTE_ID);
    }

    @Override
    public boolean onCreate() {
        NotesDatabaseHelper helper = new NotesDatabaseHelper(getContext());
        notesDB = helper.getWritableDatabase();
        return notesDB != null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sort) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(DATABASE_TABLE);

        // If this is a row query, limit the result set to the passed in row.
        switch (uriMatcher.match(uri)) {
            case NOTE_ID:
                qb.appendWhere(KEY_ID + "=" + uri.getPathSegments().get(1));
                break;
            default:
                break;
        }

        // Apply the query to the underlying database.
        Cursor c = qb.query(notesDB, projection, selection, selectionArgs, null, null, sort);

        // Register the contexts ContentResolver to be notified if
        // the cursor result set changes.
        c.setNotificationUri(getContext().getContentResolver(), uri);

        // Return a cursor to the query result.
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues initialValues) {
        // Insert the new row, will return the row number if
        // successful.
        long rowID = notesDB.insert(DATABASE_TABLE, "note", initialValues);

        // Return a URI to the newly inserted row on success.
        if (rowID > 0) {
            Uri newUri = ContentUris.withAppendedId(CONTENT_URI, rowID);
            getContext().getContentResolver().notifyChange(newUri, null);
            return newUri;
        }
        throw new SQLException("Failed to insert row into " + uri);
    }

    @Override
    public int delete(Uri uri, String where, String[] whereArgs) {
        int count;

        switch (uriMatcher.match(uri)) {
            case ALL_NOTES:
                count = notesDB.delete(DATABASE_TABLE, where, whereArgs);
                break;

            case NOTE_ID:
                String segment = uri.getPathSegments().get(1);
                StringBuilder whereClause = new StringBuilder(KEY_ID).append("=").append(segment);
                if (!TextUtils.isEmpty(where)) {
                    whereClause.append(" AND (").append(where).append(")");
                }

                count = notesDB.delete(DATABASE_TABLE, whereClause.toString(), whereArgs);
                break;

            default:
                throw new IllegalArgumentException("Unsupported URI: " + uri);
        }

        getContext().getContentResolver().notifyChange(uri, null);
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String where, String[] whereArgs) {
        int count;
        switch (uriMatcher.match(uri)) {
            case ALL_NOTES:
                count = notesDB.update(DATABASE_TABLE, values, where, whereArgs);
                break;

            case NOTE_ID:
                String segment = uri.getPathSegments().get(1);
                StringBuilder whereClause = new StringBuilder(KEY_ID).append("=").append(segment);
                if (!TextUtils.isEmpty(where)) {
                    whereClause.append(" AND (").append(where).append(")");
                }
                count = notesDB.update(DATABASE_TABLE, values, whereClause.toString(), whereArgs);
                break;

            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        getContext().getContentResolver().notifyChange(uri, null);
        return count;
    }

    @Override
    public String getType(Uri uri) {
        switch (uriMatcher.match(uri)) {
            case ALL_NOTES:
                return CONTENT_TYPE;
            case NOTE_ID:
                return CONTENT_ITEM_TYPE;
            default:
                throw new IllegalArgumentException("Unsupported URI: " + uri);
        }
    }

    // column names
    public static final String KEY_ID = "_id";
    public static final String KEY_TITLE = "title";
    public static final String KEY_BODY = "body";
    public static final String KEY_LAST_MODIFIED = "last_modified";
    public static final String KEY_DRIVE_ID = "drive_id";

    // column indexes
    public static final int ID_COLUMN = 0;
    public static final int TITLE_COLUMN = 1;
    public static final int BODY_COLUMN = 2;
    public static final int LAST_MODIFIED_COLUMN = 3;
    public static final int DRIVE_ID_COLUMN = 4;

    private static final String TAG = "NotesDbAdapter";

    private static final String DATABASE_NAME = "cloudnotes.db";
    private static final String DATABASE_TABLE = "notes";
    private static final int DATABASE_VERSION = 1;

    /**
     * Database creation sql statement
     */
    private static final String DATABASE_CREATE = "CREATE TABLE " + DATABASE_TABLE + "(" + KEY_ID
            + " INTEGER PRIMARY KEY AUTOINCREMENT, " + KEY_TITLE + " TEXT NOT NULL, " + KEY_BODY
            + " TEXT, " + KEY_LAST_MODIFIED + " INTEGER NOT NULL DEFAULT 0," + KEY_DRIVE_ID
            + " TEXT);";

    private static class NotesDatabaseHelper extends SQLiteOpenHelper {

        NotesDatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {

            db.execSQL(DATABASE_CREATE);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.w(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion
                    + ", which will destroy all old data");
            db.execSQL("DROP TABLE IF EXISTS " + DATABASE_TABLE);
            onCreate(db);
        }
    }
}
