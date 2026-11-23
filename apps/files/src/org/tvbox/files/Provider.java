package org.tvbox.files;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Locale;

/**
 * content://org.tvbox.files.provider/<absolute path>: read-only access for the app that opens a file (VLC, installer…).
 * Not exported; the viewer gets a one-off grant from the "Файлы" intent. Root-only files come through tvboxd FS_OPEN.
 */
public class Provider extends ContentProvider {
    static final String AUTHORITY = "org.tvbox.files.provider";

    static Uri uriFor(String path) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).path(path).build();
    }

    static String mime(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (ext.equals("m3u") || ext.equals("m3u8")) return "audio/x-mpegurl";
        if (ext.equals("mkv")) return "video/x-matroska";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "application/octet-stream";
    }

    private static String path(Uri uri) {
        String p = uri.getPath();
        if (p == null || !p.startsWith("/") || p.contains("/../")) throw new IllegalArgumentException("bad path");
        return p;
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return mime(path(uri)); }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("только чтение");
        return Root.open(path(uri));
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String p = path(uri);
        if (projection == null) projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(projection, 1);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) row[i] = new File(p).getName();
            else if (OpenableColumns.SIZE.equals(projection[i])) { long s = Root.size(p); row[i] = s < 0 ? null : s; }
        }
        c.addRow(row);
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
