package org.tvbox.iptv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Даёт VLC временный M3U только на чтение через одноразовое разрешение URI. */
public final class PlaylistProvider extends ContentProvider {
    static final String AUTHORITY = "org.tvbox.iptv.playlists";
    static final String MIME_TYPE = "application/x-mpegURL";

    @Override public boolean onCreate() { return true; }

    /** Проверяет URI и оставляет доступ только к M3U-файлам этой оболочки в cacheDir. */
    private File fileFor(Uri uri) throws FileNotFoundException {
        if (uri == null || !"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority()))
            throw new FileNotFoundException("неверный источник плейлиста");
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("iptv-[a-f0-9]{32}\\.m3u"))
            throw new FileNotFoundException("неверное имя плейлиста");
        try {
            File root = getContext().getCacheDir().getCanonicalFile();
            File target = new File(root, name).getCanonicalFile();
            if (!root.equals(target.getParentFile()) || !target.isFile())
                throw new FileNotFoundException("плейлист не найден");
            return target;
        } catch (IOException e) {
            throw new FileNotFoundException("не удалось проверить путь плейлиста");
        }
    }

    @Override public String getType(Uri uri) {
        try { fileFor(uri); return MIME_TYPE; }
        catch (FileNotFoundException e) { return null; }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("плейлист доступен только для чтения");
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        try {
            File file = fileFor(uri);
            String[] columns = projection == null
                    ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException e) { return null; }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
