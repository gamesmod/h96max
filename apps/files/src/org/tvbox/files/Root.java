package org.tvbox.files;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Root access for the "Файлы" tile. The app never runs su (it is limited to root:shell, see build-sd.sh); it sends
 * FS_* commands to the tvboxd root helper over the abstract socket "tvboxd", which accepts them only from this uid.
 */
final class Root {

    static final class Entry {
        final String name;
        final boolean dir, link;
        final long size, mtime;
        Entry(String name, boolean dir, boolean link, long size, long mtime) {
            this.name = name; this.dir = dir; this.link = link; this.size = size; this.mtime = mtime;
        }
    }

    /** Result of one command: ok flag, DONE message, ENTRY lines and, for FS_OPEN, the received descriptor. */
    static final class Result {
        boolean ok;
        String message = "служба tvboxd недоступна";
        final List<Entry> entries = new ArrayList<Entry>();
        ParcelFileDescriptor fd;
    }

    private Root() { }

    static Result run(String cmd) {
        Result r = new Result();
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress("tvboxd"));
            PrintWriter w = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
            w.println(cmd);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            for (String l; (l = in.readLine()) != null; ) {
                if (l.startsWith("ENTRY ")) {
                    Entry e = parse(l);
                    if (e != null) r.entries.add(e);
                } else if (l.startsWith("FD ")) {
                    FileDescriptor[] fds = s.getAncillaryFileDescriptors();
                    if (fds != null && fds.length > 0 && r.fd == null) r.fd = ParcelFileDescriptor.dup(fds[0]);
                    if (fds != null) for (FileDescriptor f : fds) closeQuietly(f);
                } else if (l.startsWith("DONE ")) {
                    r.ok = l.startsWith("DONE ok");
                    int sp = l.indexOf(' ', 5);
                    r.message = sp < 0 ? "" : l.substring(sp + 1);
                }
            }
        } catch (Exception e) {
            r.ok = false;
            r.message = "служба tvboxd недоступна: " + e.getMessage();
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
        return r;
    }

    /** "ENTRY d|f|l size mtime name" (the name is the rest of the line and may contain spaces). */
    private static Entry parse(String l) {
        String[] p = l.split(" ", 5);
        if (p.length < 5) return null;
        try {
            return new Entry(p[4], p[1].equals("d") || p[1].equals("l"), p[1].equals("l"), Long.parseLong(p[2]), Long.parseLong(p[3]));
        } catch (NumberFormatException e) { return null; }
    }

    private static void closeQuietly(FileDescriptor f) {
        try { android.system.Os.close(f); } catch (Exception ignored) { }
    }

    static boolean available() { return run("FS_STAT /").ok; }

    static Result list(String dir) { return run("FS_LIST " + dir); }

    /** Without tvboxd (e.g. an APK installed on another system) fall back to the app's own access. */
    static Result listPlain(String dir) {
        Result r = new Result();
        File[] items = new File(dir).listFiles();
        if (items == null) { r.message = "нет доступа без root"; return r; }
        for (File f : items) r.entries.add(new Entry(f.getName(), f.isDirectory(), false, f.isDirectory() ? 0 : f.length(), f.lastModified()));
        r.ok = true;
        r.message = items.length + " объектов (без root)";
        return r;
    }

    /** Read-only descriptor for any file: directly when the app may read it, otherwise opened as root by tvboxd. */
    static ParcelFileDescriptor open(String path) throws java.io.FileNotFoundException {
        File f = new File(path);
        if (f.canRead()) return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        Result r = run("FS_OPEN " + path);
        if (r.fd == null) throw new java.io.FileNotFoundException(r.message);
        return r.fd;
    }

    static long size(String path) {
        File f = new File(path);
        if (f.canRead()) return f.length();
        Result r = run("FS_STAT " + path);
        return r.ok && !r.entries.isEmpty() ? r.entries.get(0).size : -1;
    }
}
