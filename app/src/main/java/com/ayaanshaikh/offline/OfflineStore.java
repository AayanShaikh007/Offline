package com.ayaanshaikh.offline;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class OfflineStore extends SQLiteOpenHelper {
    record Playlist(PlaylistLink link, String title, String status, long checked) {}
    record Song(String playlist, String key, String title, String artist, long seconds,
                String video, String status, int attempts, long retryAt, boolean active, String file) {}
    record Event(long at, String message) {}
    record RemoteSong(String key, String title, String artist, long seconds, String video) {}
    record Snapshot(String name, List<RemoteSong> songs) {}

    private static OfflineStore instance;
    private final File audioDir;

    static synchronized OfflineStore get(Context context) {
        if (instance == null) instance = new OfflineStore(context.getApplicationContext());
        return instance;
    }

    private OfflineStore(Context context) {
        super(context, "offline.db", null, 1);
        audioDir = new File(context.getFilesDir(), "audio");
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE playlists (key TEXT PRIMARY KEY, source TEXT NOT NULL, remote TEXT NOT NULL, "
                + "title TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'queued', checked INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE songs (playlist TEXT NOT NULL REFERENCES playlists(key) ON DELETE CASCADE, "
                + "key TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, seconds INTEGER NOT NULL, "
                + "video TEXT, status TEXT NOT NULL DEFAULT 'queued', attempts INTEGER NOT NULL DEFAULT 0, "
                + "retry_at INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 1, position INTEGER NOT NULL, "
                + "PRIMARY KEY (playlist,key))");
        db.execSQL("CREATE TABLE audio (video TEXT PRIMARY KEY, file TEXT NOT NULL, title TEXT NOT NULL, "
                + "artist TEXT NOT NULL, added INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY, at INTEGER NOT NULL, message TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Missing database migration");
    }

    boolean add(PlaylistLink link) {
        ContentValues row = new ContentValues();
        row.put("key", link.key());
        row.put("source", link.source());
        row.put("remote", link.id());
        row.put("title", link.id());
        return getWritableDatabase().insertWithOnConflict("playlists", null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    void remove(PlaylistLink link) {
        getWritableDatabase().delete("playlists", "key=?", new String[]{link.key()});
        log("Untracked " + link.source() + " playlist. Saved audio kept.");
    }

    List<Playlist> playlists() {
        List<Playlist> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT source,remote,title,status,checked FROM playlists ORDER BY rowid", null)) {
            while (cursor.moveToNext()) result.add(new Playlist(new PlaylistLink(cursor.getString(0),
                    cursor.getString(1)), cursor.getString(2), cursor.getString(3), cursor.getLong(4)));
        }
        return result;
    }

    void playlistError(PlaylistLink link, String error) {
        ContentValues values = new ContentValues();
        values.put("status", error);
        values.put("checked", System.currentTimeMillis());
        getWritableDatabase().update("playlists", values, "key=?", new String[]{link.key()});
    }

    int snapshot(PlaylistLink link, Snapshot snapshot) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor row = db.rawQuery("SELECT 1 FROM playlists WHERE key=?", new String[]{link.key()})) {
                if (!row.moveToFirst()) return 0;
            }
            db.execSQL("UPDATE songs SET active=0 WHERE playlist=?", new Object[]{link.key()});
            int added = 0;
            int position = 0;
            for (RemoteSong song : snapshot.songs()) {
                ContentValues fields = new ContentValues();
                fields.put("title", song.title());
                fields.put("artist", song.artist());
                fields.put("seconds", song.seconds());
                fields.put("active", 1);
                fields.put("position", position++);
                String[] selection = {link.key(), song.key()};
                if (db.update("songs", fields, "playlist=? AND key=?", selection) == 0) {
                    fields.put("playlist", link.key());
                    fields.put("key", song.key());
                    fields.put("video", song.video());
                    db.insertOrThrow("songs", null, fields);
                    added++;
                }
            }
            ContentValues status = new ContentValues();
            status.put("title", snapshot.name());
            status.put("status", "synced");
            status.put("checked", System.currentTimeMillis());
            db.update("playlists", status, "key=?", new String[]{link.key()});
            db.setTransactionSuccessful();
            return added;
        } finally {
            db.endTransaction();
        }
    }

    List<Song> songs(String playlist) {
        return querySongs("WHERE s.playlist=? AND s.active=1 ORDER BY s.position", new String[]{playlist});
    }

    List<Song> queue(boolean force) {
        long cutoff = force ? Long.MAX_VALUE : System.currentTimeMillis();
        return querySongs("WHERE s.active=1 AND a.file IS NULL AND s.retry_at<=? "
                        + "AND s.status<>'match needed' ORDER BY s.attempts,s.position",
                new String[]{Long.toString(cutoff)});
    }

    private List<Song> querySongs(String clause, String[] arguments) {
        List<Song> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT s.playlist,s.key,s.title,s.artist,s.seconds,s.video,s.status,s.attempts,s.retry_at,s.active,a.file "
                        + "FROM songs s LEFT JOIN audio a ON a.video=s.video " + clause, arguments)) {
            while (c.moveToNext()) result.add(new Song(c.getString(0), c.getString(1), c.getString(2),
                    c.getString(3), c.getLong(4), c.getString(5), c.getString(6), c.getInt(7),
                    c.getLong(8), c.getInt(9) != 0, c.getString(10)));
        }
        return result;
    }

    List<Song> library() {
        List<Song> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT video,title,artist,file FROM audio ORDER BY added DESC", null)) {
            while (c.moveToNext()) result.add(new Song("", c.getString(0), c.getString(1), c.getString(2),
                    0, c.getString(0), "saved", 0, 0, true, c.getString(3)));
        }
        return result;
    }

    boolean isActive(Song song) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM songs WHERE playlist=? AND key=? AND active=1",
                new String[]{song.playlist(), song.key()})) {
            return c.moveToFirst();
        }
    }

    boolean setMatch(Song song, String video) {
        ContentValues values = new ContentValues();
        values.put("video", video);
        values.put("status", "queued");
        values.put("attempts", 0);
        values.put("retry_at", 0);
        return getWritableDatabase().update("songs", values, "playlist=? AND key=? AND active=1 AND video IS NULL",
                new String[]{song.playlist(), song.key()}) > 0;
    }

    void failed(Song song, String video, String status, boolean waitForMatch) {
        int attempts = song.attempts() + 1;
        long delay = Math.min(24, 1L << Math.min(attempts - 1, 5)) * 3_600_000L;
        ContentValues values = new ContentValues();
        values.put("status", status);
        values.put("attempts", attempts);
        values.put("retry_at", waitForMatch ? Long.MAX_VALUE : System.currentTimeMillis() + delay);
        String where = "playlist=? AND key=? AND video " + (video == null ? "IS NULL" : "=?");
        String[] selection = video == null ? new String[]{song.playlist(), song.key()}
                : new String[]{song.playlist(), song.key(), video};
        getWritableDatabase().update("songs", values, where, selection);
    }

    void retry() {
        getWritableDatabase().execSQL("UPDATE songs SET retry_at=0,attempts=0,status='queued' "
                + "WHERE status<>'match needed'");
    }

    File file(String name) { return new File(audioDir, name); }
    void ensureAudioDir() throws java.io.IOException {
        if (!audioDir.isDirectory() && !audioDir.mkdirs()) throw new java.io.IOException("Audio folder unavailable");
    }

    boolean hasAudio(String video) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT file FROM audio WHERE video=?", new String[]{video})) {
            return c.moveToFirst() && file(c.getString(0)).isFile();
        }
    }

    void reconcileAudio() {
        for (Song song : library()) {
            if (!file(song.file()).isFile()) {
                getWritableDatabase().delete("audio", "video=?", new String[]{song.video()});
            }
        }
    }

    void saved(Song song, String video, String filename) {
        ContentValues row = new ContentValues();
        row.put("video", video);
        row.put("file", filename);
        row.put("title", song.title());
        row.put("artist", song.artist());
        row.put("added", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("audio", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void clearSpotify() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("playlists", "source=?", new String[]{PlaylistLink.SPOTIFY});
        db.delete("events", null, null);
    }

    synchronized void log(String message) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("INSERT INTO events(at,message) VALUES (?,?)", new Object[]{System.currentTimeMillis(), message});
        db.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 80)");
    }

    List<Event> events() {
        List<Event> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT at,message FROM events ORDER BY id DESC", null)) {
            while (c.moveToNext()) result.add(new Event(c.getLong(0), c.getString(1)));
        }
        return result;
    }
}
