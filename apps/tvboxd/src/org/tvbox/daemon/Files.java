package org.tvbox.daemon;

import android.net.LocalSocket;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Root file operations for the "Файлы" tile (apps/files, org.tvbox.files). The app itself never gets su: like the other
 * tiles it asks tvboxd over the abstract socket, and only its uid (and root/shell for debugging) may use FS_* commands.
 * Paths are absolute; two-path commands separate them with a tab. File operations use Java/Os calls, not a shell.
 * Commands:
 *   FS_LIST dir            "ENTRY d|f|l size mtime_ms name" lines, then DONE
 *   FS_STAT path           one ENTRY line for the path itself
 *   FS_MKDIR path          create a folder
 *   FS_RENAME src\tdst     rename/move inside one file system
 *   FS_COPY src\tdir       copy a file or a folder (recursively) into dir
 *   FS_MOVE src\tdir       move into dir (rename, or copy + delete across file systems)
 *   FS_DELETE path         delete a file or a folder recursively (symlinks are removed, never followed)
 *   FS_OPEN path           open the file read-only as root and pass the descriptor to the client ("FD" line)
 */
class Files {

    static void handle(LocalSocket s, Main.Op op, String cmd) {
        int sp = cmd.indexOf(' ');
        String verb = sp < 0 ? cmd : cmd.substring(0, sp), arg = sp < 0 ? "" : cmd.substring(sp + 1);
        try {
            if (verb.equals("FS_LIST")) { list(op, path(arg)); return; }
            if (verb.equals("FS_STAT")) { File f = path(arg); if (!exists(f)) { op.done(false, "нет такого файла"); return; } entry(op, f); op.done(true, "файл"); return; }
            if (verb.equals("FS_MKDIR")) { File f = path(arg); op.done(f.mkdirs() || f.isDirectory(), "папка " + f.getName()); return; }
            if (verb.equals("FS_DELETE")) { delete(op, path(arg)); return; }
            if (verb.equals("FS_OPEN")) { open(s, op, path(arg)); return; }
            String[] two = arg.split("\t", -1);
            if (two.length != 2) { op.done(false, "нужны два пути"); return; }
            File src = path(two[0]), dst = path(two[1]);
            if (!exists(src)) { op.done(false, "нет такого файла: " + src); return; }
            if (verb.equals("FS_RENAME")) {
                if (exists(dst)) { op.done(false, "уже существует: " + dst.getName()); return; }
                op.done(src.renameTo(dst), "переименовано в " + dst.getName());
                return;
            }
            if (verb.equals("FS_COPY") || verb.equals("FS_MOVE")) {
                if (!dst.isDirectory()) { op.done(false, "папка назначения недоступна"); return; }
                String dstCanon = dst.getCanonicalPath() + "/", srcCanon = src.getCanonicalPath() + "/";
                if (src.isDirectory() && !isLink(src) && dstCanon.startsWith(srcCanon)) { op.done(false, "нельзя копировать папку в саму себя"); return; }
                File target = freeName(dst, src.getName());
                boolean move = verb.equals("FS_MOVE");
                if (move && src.renameTo(target)) { op.done(true, "перемещено: " + target.getName()); return; }
                long[] n = {0};
                copy(op, src, target, n);
                if (move) remove(src);
                op.done(true, (move ? "перемещено: " : "скопировано: ") + target.getName() + " (" + n[0] + " файлов)");
                return;
            }
            op.done(false, "неизвестная команда " + verb);
        } catch (Throwable t) {
            op.done(false, "ошибка: " + t.getMessage());
        }
    }

    static File path(String p) {
        if (!p.startsWith("/") || p.indexOf('\0') >= 0 || p.indexOf('\n') >= 0) throw new IllegalArgumentException("неверный путь");
        return new File(p);
    }

    static boolean exists(File f) { return f.exists() || isLink(f); }

    static boolean isLink(File f) {
        try { return OsConstants.S_ISLNK(Os.lstat(f.getPath()).st_mode); } catch (Exception e) { return false; }
    }

    static void list(Main.Op op, File dir) {
        File[] items = dir.listFiles();
        if (items == null) { op.done(false, dir.isDirectory() ? "нет доступа к папке" : "папка не найдена"); return; }
        for (File f : items) entry(op, f);
        op.done(true, items.length + " объектов");
    }

    static void entry(Main.Op op, File f) {
        String name = f.getName().isEmpty() ? "/" : f.getName();
        if (name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) return;
        String type = isLink(f) ? (f.isDirectory() ? "l" : "f") : f.isDirectory() ? "d" : "f";
        op.send("ENTRY " + type + " " + (f.isDirectory() ? 0 : f.length()) + " " + f.lastModified() + " " + name);
    }

    /** The top system folders and the storage roots stay: deleting them would break the box or every mount. */
    static void delete(Main.Op op, File f) throws Exception {
        String abs = f.getAbsolutePath();
        int depth = 0;
        for (String part : abs.split("/")) if (!part.isEmpty()) depth++;
        if (depth < 2 || abs.equals("/storage/emulated") || abs.equals("/storage/emulated/0") || abs.equals("/data/media/0")) {
            op.done(false, "системную папку удалять нельзя: " + abs);
            return;
        }
        if (!exists(f)) { op.done(false, "нет такого файла"); return; }
        op.done(remove(f), "удалено: " + f.getName());
    }

    static boolean remove(File f) {
        if (f.isDirectory() && !isLink(f)) {
            File[] items = f.listFiles();
            if (items != null) for (File c : items) remove(c);
        }
        return f.delete();
    }

    static File freeName(File dir, String name) {
        File t = new File(dir, name);
        if (!exists(t)) return t;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            t = new File(dir, base + " (копия" + (i > 1 ? " " + i : "") + ")" + ext);
            if (!exists(t)) return t;
        }
    }

    static void copy(Main.Op op, File src, File dst, long[] n) throws Exception {
        if (isLink(src)) { Os.symlink(Os.readlink(src.getPath()), dst.getPath()); n[0]++; return; }
        if (src.isDirectory()) {
            if (!dst.mkdirs() && !dst.isDirectory()) throw new Exception("не создана папка " + dst);
            File[] items = src.listFiles();
            if (items != null) for (File c : items) copy(op, c, new File(dst, c.getName()), n);
            return;
        }
        byte[] buf = new byte[256 << 10];
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
        }
        dst.setLastModified(src.lastModified());
        if (++n[0] % 50 == 0) op.send("LOG скопировано файлов: " + n[0]);
    }

    /** Pass a read-only descriptor (SCM_RIGHTS) with the "FD" line: the app's provider hands it to the viewer app. */
    static void open(LocalSocket s, Main.Op op, File f) throws Exception {
        if (!f.isFile()) { op.done(false, "не файл"); return; }
        try (FileInputStream in = new FileInputStream(f)) {
            s.setFileDescriptorsForSend(new FileDescriptor[]{in.getFD()});
            op.send("FD " + f.length());
            s.setFileDescriptorsForSend(null);
            op.done(true, "открыт " + f.getName());
        }
    }
}
