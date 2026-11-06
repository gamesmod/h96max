package org.tvbox.daemon;

import android.net.LocalServerSocket;
import android.net.LocalSocket;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * tvboxd: root helper for the "Установка" tile (eMMC install/verify/backup/restore, updates) and the "Антивирус"
 * tile (Hypatia signatures, realtime scanner) and the "Производительность" tile (metrics while it is open) — everything on demand, no background jobs. Runs via app_process as an init service. Clients talk over the abstract
 * local socket "tvboxd": one command line in, "PROGRESS pct text" / "LOG text" / "KEY value" lines out,
 * last line "DONE ok|fail message". Only the installer app's uid (and root/shell for debugging) may connect.
 * All sizes are long: Android mksh arithmetic is 32-bit, Java is not.
 */
public class Main {
    static final String EMMC = "/dev/block/mmcblk2";
    static final String EMMC_SYS = "/sys/block/mmcblk2";
    static final String IMG_DEV = "/dev/block/by-name/tvbox_emmc";
    static final String IMG_MNT = "/mnt/tvbox_emmc";
    static final String IMG_DATA = "/data/vendor/tvbox/emmc-image";   // fallback: image copied onto the SD userdata
    static volatile String IMG_DIR = IMG_MNT;
    static final String STATE = "/data/vendor/tvbox";
    static final String BACKUP_DIR = STATE + "/emmc-backup";
    static final String LAST_LOG = STATE + "/last-operation.log";
    static final String INSTALLER = "org.tvbox.installer";
    static final String ANTIVIRUS = "org.tvbox.antivirus";   // may only use the AV_* / HYPATIA_UPDATE commands
    static final String POWER = "org.tvbox.power";           // may only use STATUS / REBOOT_EMMC
    static final String PERF = "org.tvbox.clean";            // "Производительность": only the PERF_* commands
    static final String EMMC_OK = STATE + "/emmc-verified";  // md5 of the image manifest the eMMC last matched
    static final String SD = "/dev/block/mmcblk0";
    static final byte[] EMMCBOOT_TAG = "TVBOX-EMMCBOOT".getBytes();   // sector 63 of the SD, see tvbox-sdboot.sh
    static final String HYPATIA = "org.maintainteam.hypatia";
    static final String HYPATIA_DB = "https://maintainteam.github.io/HypatiaDatabases/";
    static final String[] HYPATIA_FILES = {"gpg.key", "hypatia-md5-bloom.bin", "hypatia-md5-bloom.bin.sig",
        "hypatia-sha1-bloom.bin", "hypatia-sha1-bloom.bin.sig", "hypatia-sha256-bloom.bin", "hypatia-sha256-bloom.bin.sig"};
    static final long CHUNK = 512L << 20;
    static final String OTA_DIR = STATE + "/ota";
    static final String OTA_PKG = OTA_DIR + "/update.zip";
    // recovery unmounts /data before installing (setup_install_mounts), so it reads the package through a block
    // map made by the system's uncrypt service (same as RecoverySystem.processPackage); /cache stays mounted
    static final String BLOCK_MAP = "/cache/recovery/block.map";
    static final String UNCRYPT_FILE = "/cache/recovery/uncrypt_file";
    static final String WIPE_FLAG = "/cache/recovery/tvbox-wipe-requested";
    static final String URLS_USER = STATE + "/update-urls.txt";
    static final String URLS_DEFAULT = "/vendor/etc/tvbox/update-urls.txt";
    static final Object opLock = new Object();
    static volatile String busy = "";

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("client")) { client(args[1]); return; }
        if (args.length == 2 && args[0].equals("uncrypt")) {   // debug: block map for a file, no misc/reboot
            Op op = new Op(new PrintWriter(System.out, true));
            System.out.println(uncrypt(op, args[1]) ? "UNCRYPT OK\n" + read(BLOCK_MAP) : "UNCRYPT FAIL");
            return;
        }
        if (args.length == 1 && args[0].equals("perf")) {   // debug: print PERF_NOW
            Perf.now(new Op(new PrintWriter(System.out, true)));
            return;
        }
        if (args.length == 3 && args[0].equals("verify")) {   // debug: verify an OTA against a certs zip
            try { android.os.RecoverySystem.verifyPackage(new File(args[1]), null, new File(args[2])); System.out.println("VERIFY OK"); }
            catch (Exception e) { System.out.println("VERIFY FAIL " + e); }
            return;
        }
        new File(STATE).mkdirs();
        // a package that is still here after a normal boot was installed (or failed): free the space
        if (new File(OTA_PKG).exists()) {
            log("OTA: удаляю установленный пакет, текущий выпуск " + prop("ro.tvbox.release"));
            new File(OTA_PKG).delete();
            new File(OTA_DIR + "/wipe-requested").delete();
            new File(WIPE_FLAG).delete();
            new File(BLOCK_MAP).delete();
            new File(UNCRYPT_FILE).delete();
        }
        clearStaleBcb();
        LocalServerSocket server = new LocalServerSocket("tvboxd");
        log("tvboxd started");
        while (true) {
            final LocalSocket s = server.accept();
            new Thread(new Runnable() {
                public void run() { serve(s); }
            }).start();
        }
    }

    // ---------------------------------------------------------------- client handling

    /** Debug client: app_process ... org.tvbox.daemon.Main client STATUS */
    static void client(String cmd) throws Exception {
        LocalSocket s = new LocalSocket();
        s.connect(new android.net.LocalSocketAddress("tvboxd"));
        PrintWriter w = new PrintWriter(s.getOutputStream(), true);
        w.println(cmd);
        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        for (String l; (l = r.readLine()) != null; ) System.out.println(l);
        s.close();
    }

    static void serve(LocalSocket s) {
        PrintWriter out = null;
        try {
            out = new PrintWriter(new java.io.OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
            int uid = s.getPeerCredentials().getUid();
            boolean full = uid == 0 || uid == 2000 || uid == uidOf(INSTALLER), av = uid == uidOf(ANTIVIRUS), pw = uid == uidOf(POWER),
                    pf = uid == uidOf(PERF);
            if (!full && !av && !pw && !pf) {
                out.println("DONE fail доступ запрещён (uid " + uid + ")");
                return;
            }
            String cmd = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8")).readLine();
            if (cmd == null) return;
            cmd = cmd.trim();
            if (!full && !(av && (cmd.startsWith("AV_") || cmd.equals("HYPATIA_UPDATE")))
                      && !(pw && (cmd.equals("STATUS") || cmd.equals("REBOOT_EMMC")))
                      && !(pf && cmd.startsWith("PERF_"))) {
                out.println("DONE fail команда недоступна этому приложению");
                return;
            }
            Op op = new Op(out);
            if (cmd.equals("STATUS")) { status(op); op.done(true, "состояние"); return; }
            if (cmd.equals("LOG")) { op.dumpLast(); op.done(true, "журнал"); return; }
            if (cmd.equals("UPDATE_CHECK")) { updateCheck(op); return; }
            if (cmd.equals("AV_SCAN")) { avScan(op); return; }
            if (cmd.equals("AV_STATUS")) { avStatus(op); op.done(true, "антивирус"); return; }
            if (cmd.equals("AV_REALTIME on") || cmd.equals("AV_REALTIME off")) { avRealtime(cmd.endsWith("on")); avStatus(op); op.done(true, "защита " + (cmd.endsWith("on") ? "включена" : "выключена")); return; }
            if (cmd.equals("PERF_NOW")) { Perf.now(op); op.done(true, "показатели"); return; }
            if (cmd.equals("PERF_FREE")) { Perf.free(op); return; }
            if (cmd.equals("PERF_ZRAM")) { Perf.zramStatus(op); op.done(true, "сжатие памяти"); return; }
            if (cmd.startsWith("PERF_ZRAM_SET ")) { Perf.setZram(op, cmd.substring(14).trim()); return; }
            if (cmd.equals("SD_IMAGES")) { sdImages(op); return; }
            if (cmd.equals("URLS")) { for (String u : updateUrls()) op.kv("URL", u); op.done(true, "источники"); return; }
            if (cmd.startsWith("URL_ADD ")) { editUrls(cmd.substring(8).trim(), true); op.done(true, "добавлено"); return; }
            if (cmd.startsWith("URL_DEL ")) { editUrls(cmd.substring(8).trim(), false); op.done(true, "удалено"); return; }
            if (!busy.isEmpty()) { op.done(false, "уже выполняется: " + busy); return; }
            synchronized (opLock) {
                busy = cmd;
                op.startLog(cmd);
                try {
                    if (cmd.equals("INSTALL") || cmd.equals("INSTALL_WIFI") || cmd.equals("RESTORE")) new File(EMMC_OK).delete();
                    if (cmd.equals("INSTALL")) install(op);
                    else if (cmd.equals("INSTALL_WIFI")) install(op, true);
                    else if (cmd.equals("VERIFY")) verifyImageOnEmmc(op, true);
                    else if (cmd.equals("BACKUP")) backup(op);
                    else if (cmd.equals("RESTORE")) restore(op);
                    else if (cmd.equals("REBOOT_EMMC")) rebootEmmc(op);
                    else if (cmd.startsWith("INSTALL_SD ")) installSd(op, cmd.substring(11).trim());
                    else if (cmd.equals("HYPATIA_UPDATE")) op.done(updateHypatia(op), "обновление баз Hypatia");
                    else if (cmd.startsWith("UPDATE_INSTALL ")) updateInstall(op, cmd.substring(15).trim());
                    else op.done(false, "неизвестная команда " + cmd);
                } catch (Throwable t) {
                    op.done(false, "ошибка: " + t);
                } finally {
                    setEmmcReadOnly(true);
                    busy = "";
                }
            }
        } catch (Throwable t) {
            if (out != null) out.println("DONE fail " + t);
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /** Output to the client (best effort: the user may leave the screen) and to the operation log. */
    static class Op {
        final PrintWriter out;
        PrintWriter file;
        boolean finished;
        Op(PrintWriter out) { this.out = out; }

        void startLog(String cmd) {
            try { file = new PrintWriter(new FileOutputStream(LAST_LOG), true); } catch (Exception ignored) { }
            log("НАЧАЛО " + cmd);
        }
        void send(String line) { try { out.println(line); } catch (Throwable ignored) { } }
        void kv(String k, String v) { send(k + " " + v); }
        void log(String s) {
            String line = now() + "  " + s;
            send("LOG " + line);
            if (file != null) file.println(line);
            Main.log(s);
        }
        void progress(long done, long total, String text) {
            int pct = total > 0 ? (int) (done * 100 / total) : 0;
            send("PROGRESS " + pct + " " + text);
            String line = now() + "  " + pct + "%  " + text;
            send("LOG " + line);
            if (file != null) file.println(line);
            Main.log("операция: " + pct + "% " + text);
        }
        void done(boolean ok, String msg) {
            if (finished) return;
            finished = true;
            log((ok ? "ГОТОВО: " : "ОШИБКА: ") + msg);
            send("DONE " + (ok ? "ok " : "fail ") + msg);
            if (file != null) file.close();
        }
        void dumpLast() {
            try (BufferedReader r = new BufferedReader(new FileReader(LAST_LOG))) {
                for (String l; (l = r.readLine()) != null; ) send("LOG " + l);
            } catch (Exception e) { send("LOG журнала ещё нет"); }
        }
    }

    // ---------------------------------------------------------------- status

    static void status(Op op) {
        op.kv("BOOT", bootedFromSd() ? "sd" : "emmc");
        op.kv("RELEASE", prop("ro.tvbox.release"));
        op.kv("EMMC_OK", isEmmcDevice() ? "1" : "0");
        op.kv("EMMC_RO", read(EMMC_SYS + "/force_ro").trim());
        op.kv("EMMC_CONTENT", emmcContent());
        List<String[]> m = imageManifest();
        op.kv("IMAGE", m == null ? "нет" : "выпуск " + read(IMG_DIR + "/release.txt").trim() + " (" + m.size() + " регионов, " + (IMG_DIR.equals(IMG_MNT) ? "раздел tvbox_emmc" : "папка на SD") + ")");
        op.kv("BACKUP", backupInfo());
        op.kv("SD_FREE_MB", String.valueOf(new File("/data").getUsableSpace() >> 20));
        op.kv("BUSY", busy.isEmpty() ? "-" : busy);
    }

    static boolean bootedFromSd() { return read("/proc/cmdline").contains("androidboot.boot_devices=ff500000"); }

    static boolean isEmmcDevice() {
        try { return new File(EMMC_SYS + "/device").getCanonicalPath().contains("ff520000.dwmmc"); }
        catch (Exception e) { return false; }
    }

    /** What is on the eMMC now: our image (same boot region as the packed image), another A12 layout, or stock A9. */
    static String emmcContent() {
        List<String> names = gptNames(EMMC);
        if (names.isEmpty()) return "неизвестно (нет GPT)";
        if (names.contains("super")) {
            List<String[]> m = imageManifest();
            if (m != null) {
                for (String[] r : m) {
                    if (!r[2].endsWith("-boot.bin")) continue;
                    try {
                        String md5 = md5Device(EMMC, Long.parseLong(r[0]) * 512, Long.parseLong(r[1]), null, null);
                        return md5.equals(r[3]) ? "tvbox (как образ на SD)" : "Android 12 (другая версия)";
                    } catch (Exception e) { return "Android 12 (не удалось прочитать)"; }
                }
            }
            return "Android 12";
        }
        if (names.contains("system")) return "стоковый Android 9";
        return "неизвестно";
    }

    static List<String> gptNames(String dev) {
        List<String> names = new ArrayList<>();
        try (RandomAccessFile f = new RandomAccessFile(dev, "r")) {
            byte[] h = new byte[512];
            f.seek(512); f.readFully(h);
            if (!new String(h, 0, 8, "US-ASCII").equals("EFI PART")) return names;
            long lba = le(h, 72, 8); int n = (int) le(h, 80, 4); int sz = (int) le(h, 84, 4);
            byte[] e = new byte[n * sz];
            f.seek(lba * 512); f.readFully(e);
            for (int i = 0; i < n; i++) {
                String name = new String(e, i * sz + 56, 72, "UTF-16LE");
                int z = name.indexOf('\0');
                if (z >= 0) name = name.substring(0, z);
                if (!name.isEmpty()) names.add(name);
            }
        } catch (Exception ignored) { }
        return names;
    }

    static long le(byte[] b, int off, int len) {
        long v = 0;
        for (int i = len - 1; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xff);
        return v;
    }

    static String backupInfo() {
        File[] f = new File(BACKUP_DIR).listFiles();
        int ok = 0;
        if (f != null) for (File x : f) if (x.getName().endsWith(".ok")) ok++;
        return ok == 0 ? "нет" : ok + " из " + chunks() + " частей";
    }

    static int chunks() {
        long bytes = Long.parseLong(read(EMMC_SYS + "/size").trim()) * 512;
        return (int) ((bytes + CHUNK - 1) / CHUNK);
    }

    // ---------------------------------------------------------------- image on the SD

    /** Mounts the tvbox_emmc partition read-only and returns its manifest rows {off, size, name, md5}. */
    static List<String[]> imageManifest() {
        if (!new File(IMG_MNT + "/manifest.txt").exists() && new File(IMG_DEV).exists()) {
            new File(IMG_MNT).mkdirs();
            sh("mount -t ext4 -o ro " + IMG_DEV + " " + IMG_MNT);
        }
        IMG_DIR = new File(IMG_MNT + "/manifest.txt").exists() ? IMG_MNT : IMG_DATA;
        File man = new File(IMG_DIR + "/manifest.txt");
        if (!man.exists()) return null;
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(man))) {
            for (String l; (l = r.readLine()) != null; ) {
                String[] p = l.trim().split("\\s+");
                if (p.length == 4) rows.add(p);
            }
        } catch (Exception e) { return null; }
        return rows.isEmpty() ? null : rows;
    }

    static long total(List<String[]> m) {
        long t = 0;
        for (String[] r : m) t += Long.parseLong(r[1]);
        return t;
    }

    // ---------------------------------------------------------------- install / verify

    static boolean preflight(Op op, boolean write) {
        if (!isEmmcDevice()) { op.done(false, "mmcblk2 — не eMMC (ff520000)"); return false; }
        if (write && !bootedFromSd()) { op.done(false, "система загружена с eMMC — запись eMMC только при загрузке с SD"); return false; }
        if (read("/proc/mounts").contains("mmcblk2")) { op.done(false, "разделы eMMC смонтированы"); return false; }
        return true;
    }

    static void install(Op op) throws Exception {
        install(op, false);
    }

    static void install(Op op, boolean keepWifi) throws Exception {
        if (!preflight(op, true)) return;
        List<String[]> m = imageManifest();
        if (m == null) { op.done(false, "нет образа eMMC на SD (раздел tvbox_emmc)"); return; }
        long all = total(m);
        op.log("Шаг 1/3: проверка образа на SD (" + (all >> 20) + " МиБ)");
        long done = 0;
        for (String[] r : m) {
            String got = md5Gz(IMG_DIR + "/regions/" + r[2] + ".gz", op, done, all, "проверка " + r[2]);
            if (!got.equals(r[3])) { op.done(false, "образ повреждён: " + r[2]); return; }
            done += Long.parseLong(r[1]);
        }
        if (keepWifi && !saveWifiForEmmc(op)) return;
        op.log("Шаг 2/3: запись на внутреннюю память");
        setEmmcReadOnly(false);
        done = 0;
        byte[] buf = new byte[1 << 20];
        try (RandomAccessFile dev = new RandomAccessFile(EMMC, "rw")) {
            for (String[] r : m) {
                long off = Long.parseLong(r[0]) * 512, size = Long.parseLong(r[1]);
                op.log("запись " + r[2] + " @" + r[0]);
                dev.seek(off);
                long w = 0;
                try (InputStream in = new GZIPInputStream(new FileInputStream(IMG_DIR + "/regions/" + r[2] + ".gz"), 1 << 16)) {
                    for (int n; (n = in.read(buf)) > 0; ) {
                        dev.write(buf, 0, n); w += n;
                        if ((w & ((32L << 20) - 1)) < n) op.progress(done + w, all, "запись " + r[2]);
                    }
                }
                if (w != size) { op.done(false, "размер " + r[2] + ": " + w + " вместо " + size); return; }
                done += size;
            }
            dev.getFD().sync();
        } finally {
            setEmmcReadOnly(true);
        }
        sh("echo 3 > /proc/sys/vm/drop_caches");
        op.log("Шаг 3/3: сверка записанного");
        verifyImageOnEmmc(op, true);
    }

    /** Сохраняет профиль Wi‑Fi на SD перед очисткой eMMC, не выводя содержимое XML в лог. */
    static boolean saveWifiForEmmc(Op op) {
        if (!IMG_MNT.equals(IMG_DIR)) { op.done(false, "сохранение Wi‑Fi требует установочного раздела tvbox_emmc на SD"); return false; }
        String[] sources = {
            "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
            "/data/misc/wifi/WifiConfigStore.xml",
            "/data/misc_ce/0/apexdata/com.android.wifi/WifiConfigStore.xml"
        };
        File source = null;
        for (String path : sources) { File f = new File(path); if (f.isFile() && f.length() > 0) { source = f; break; } }
        if (source == null) { op.done(false, "не найден файл настроек Wi‑Fi; установка отменена до записи"); return false; }
        File mount = new File(IMG_MNT);
        File targetDir = new File(IMG_MNT + "/network-restore");
        File target = new File(targetDir, "WifiConfigStore.xml");
        try {
            if (sh("mount -o remount,rw " + IMG_MNT) != 0) { op.done(false, "не удалось подготовить SD для сохранения Wi‑Fi"); return false; }
            if (!targetDir.isDirectory() && !targetDir.mkdirs()) throw new Exception("mkdir");
            File tmp = new File(targetDir, "WifiConfigStore.xml.tmp");
            try (InputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] b = new byte[8192]; for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                out.getFD().sync();
            }
            if (!tmp.setReadable(false, false) || !tmp.setWritable(false, false)
                    || !tmp.setReadable(true, true) || !tmp.setWritable(true, true)
                    || !tmp.renameTo(target)) throw new Exception("write");
            op.log("Настройки Wi‑Fi сохранены на SD в закрытом файле");
        } catch (Exception e) {
            new File(targetDir, "WifiConfigStore.xml.tmp").delete();
            op.done(false, "не удалось сохранить настройки Wi‑Fi; установка отменена до записи");
            return false;
        } finally {
            sh("mount -o remount,ro " + IMG_MNT);
        }
        return true;
    }

    static boolean verifyImageOnEmmc(Op op, boolean report) throws Exception {
        if (!preflight(op, false)) return false;
        List<String[]> m = imageManifest();
        if (m == null) { op.done(false, "нет образа eMMC на SD"); return false; }
        long all = total(m), done = 0;
        sh("echo 3 > /proc/sys/vm/drop_caches");
        for (String[] r : m) {
            long size = Long.parseLong(r[1]);
            String got = md5Device(EMMC, Long.parseLong(r[0]) * 512, size, op, new long[]{done, all});
            if (!got.equals(r[3])) { op.done(false, "не совпадает: " + r[2]); return false; }
            op.log("совпадает " + r[2]);
            done += size;
        }
        write(EMMC_OK, manifestId());
        if (report) op.done(true, "внутренняя память совпадает с образом (" + m.size() + " регионов)");
        return true;
    }

    static String manifestId() {
        try { return hex(MessageDigest.getInstance("MD5").digest(read(IMG_DIR + "/manifest.txt").getBytes("UTF-8"))); }
        catch (Exception e) { return "?"; }
    }

    // ---------------------------------------------------------------- one-shot eMMC boot

    /**
     * Next boot from the eMMC although the SD stays inserted. U-Boot (rkimg_bootdev) prefers the SD only when
     * rkimgtest finds the IDB magic in its sector 64: the original 4 bytes go to sector 63 behind EMMCBOOT_TAG,
     * sector 64 gets them inverted, and the eMMC system (tvbox-sdboot.sh, init "on fs") puts them back.
     * Only allowed when the eMMC was verified against the image on this SD: that image carries tvbox-sdboot.sh.
     */
    static void rebootEmmc(Op op) throws Exception {
        if (!bootedFromSd()) { op.done(false, "система уже загружена с внутренней памяти"); return; }
        if (!isEmmcDevice()) { op.done(false, "внутренняя память не найдена"); return; }
        if (!new File("/sys/block/mmcblk0/device").getCanonicalPath().contains("ff500000.dwmmc")) { op.done(false, "карта SD не найдена"); return; }
        if (!manifestId().equals(read(EMMC_OK).trim()) || !emmcContent().startsWith("tvbox")) {
            op.done(false, "внутренняя память не сверена с образом этой карты: «Установка» → «Установить» или «Проверить»");
            return;
        }
        byte[] s = new byte[1024];
        try (RandomAccessFile f = new RandomAccessFile(SD, "r")) { f.seek(63 * 512); f.readFully(s); }
        byte[] magic = {0x3b, (byte) 0x8c, (byte) 0xdc, (byte) 0xfc};
        if (!java.util.Arrays.equals(java.util.Arrays.copyOfRange(s, 512, 516), magic)) {
            op.done(false, "загрузочный сектор карты не распознан: " + hex(java.util.Arrays.copyOfRange(s, 512, 516)));
            return;
        }
        for (int i = 0; i < 512; i++) if (s[i] != 0) { op.done(false, "сектор 63 карты не пуст, отказ"); return; }
        System.arraycopy(EMMCBOOT_TAG, 0, s, 0, EMMCBOOT_TAG.length);
        System.arraycopy(magic, 0, s, 16, 4);
        for (int i = 0; i < 4; i++) s[512 + i] = (byte) ~magic[i];
        op.log("карта: метка в секторе 63, загрузчик SD отключён на одну загрузку");
        try (RandomAccessFile f = new RandomAccessFile(SD, "rw")) {
            f.seek(63 * 512); f.write(s, 0, 512); f.getFD().sync();   // the backup first, then the change
            f.seek(64 * 512); f.write(s, 512, 512); f.getFD().sync();
        }
        sh("echo 3 > /proc/sys/vm/drop_caches");
        byte[] back = new byte[1024];
        try (RandomAccessFile f = new RandomAccessFile(SD, "r")) { f.seek(63 * 512); f.readFully(back); }
        if (!java.util.Arrays.equals(s, back)) { op.done(false, "сверка секторов карты не прошла"); return; }
        op.done(true, "перезагрузка с внутренней памяти");
        sleep(1000);
        sh("setprop sys.powerctl reboot");
    }

    // ---------------------------------------------------------------- backup / restore (whole eMMC, 512 MiB gz chunks)

    static void backup(Op op) throws Exception {
        if (!preflight(op, false)) return;
        new File(BACKUP_DIR).mkdirs();
        int n = chunks();
        long disk = Long.parseLong(read(EMMC_SYS + "/size").trim()) * 512;
        byte[] buf = new byte[1 << 20];
        for (int i = 0; i < n; i++) {
            File ok = new File(BACKUP_DIR + "/c" + i + ".ok");
            if (ok.exists()) continue;
            if (new File("/data").getUsableSpace() < (1L << 30)) { op.done(false, "на SD меньше 1 ГиБ свободно (часть " + i + ")"); return; }
            long off = i * CHUNK, len = Math.min(CHUNK, disk - off);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (RandomAccessFile dev = new RandomAccessFile(EMMC, "r");
                 OutputStream gz = new GZIPOutputStream(new FileOutputStream(BACKUP_DIR + "/c" + i + ".gz"), 1 << 16)) {
                dev.seek(off);
                for (long left = len; left > 0; ) {
                    int k = dev.read(buf, 0, (int) Math.min(buf.length, left));
                    if (k <= 0) throw new Exception("короткое чтение eMMC");
                    sha.update(buf, 0, k); gz.write(buf, 0, k); left -= k;
                }
            }
            write(BACKUP_DIR + "/c" + i + ".sha", hex(sha.digest()));
            String back = sha256Gz(BACKUP_DIR + "/c" + i + ".gz");
            if (!back.equals(read(BACKUP_DIR + "/c" + i + ".sha").trim())) { op.done(false, "часть " + i + " записалась с ошибкой"); return; }
            ok.createNewFile();
            op.progress(i + 1, n, "резервная копия: часть " + (i + 1) + " из " + n);
            op.log("часть " + i + " OK");
        }
        op.done(true, "резервная копия eMMC готова (" + n + " частей) в " + BACKUP_DIR);
    }

    static void restore(Op op) throws Exception {
        if (!preflight(op, true)) return;
        int n = chunks();
        for (int i = 0; i < n; i++)
            if (!new File(BACKUP_DIR + "/c" + i + ".ok").exists()) { op.done(false, "резервная копия неполная: нет части " + i); return; }
        op.log("Проверка резервной копии");
        for (int i = 0; i < n; i++) {
            if (!sha256Gz(BACKUP_DIR + "/c" + i + ".gz").equals(read(BACKUP_DIR + "/c" + i + ".sha").trim())) {
                op.done(false, "часть " + i + " резервной копии повреждена"); return;
            }
            op.progress(i + 1, n * 3L, "проверка копии");
        }
        setEmmcReadOnly(false);
        byte[] buf = new byte[1 << 20];
        try (RandomAccessFile dev = new RandomAccessFile(EMMC, "rw")) {
            for (int i = 0; i < n; i++) {
                dev.seek(i * CHUNK);
                try (InputStream in = new GZIPInputStream(new FileInputStream(BACKUP_DIR + "/c" + i + ".gz"), 1 << 16)) {
                    for (int k; (k = in.read(buf)) > 0; ) dev.write(buf, 0, k);
                }
                op.progress(n + i + 1, n * 3L, "восстановление: часть " + (i + 1) + " из " + n);
            }
            dev.getFD().sync();
        } finally {
            setEmmcReadOnly(true);
        }
        sh("echo 3 > /proc/sys/vm/drop_caches");
        long disk = Long.parseLong(read(EMMC_SYS + "/size").trim()) * 512;
        for (int i = 0; i < n; i++) {
            long len = Math.min(CHUNK, disk - i * CHUNK);
            if (!sha256Device(EMMC, i * CHUNK, len).equals(read(BACKUP_DIR + "/c" + i + ".sha").trim())) {
                op.done(false, "после восстановления не совпадает часть " + i); return;
            }
            op.progress(2L * n + i + 1, n * 3L, "сверка: часть " + (i + 1) + " из " + n);
        }
        op.done(true, "внутренняя память восстановлена из резервной копии и сверена");
    }

    /** Read-only protects the eMMC only while the system runs from the SD card; booted from the eMMC it is the
     *  system disk and must stay writable (an "ro" eMMC made misc/BCB writes fail with EPERM, 2026-10-27). */
    static void setEmmcReadOnly(boolean ro) {
        if (ro && !bootedFromSd()) ro = false;
        write(EMMC_SYS + "/force_ro", ro ? "1" : "0");
        sh("blockdev " + (ro ? "--setro " : "--setrw ") + EMMC);
    }

    // ---------------------------------------------------------------- Hypatia signatures (daily)

    static final String HYPATIA_RT = HYPATIA + "/us.spotco.malwarescanner.MalwareScannerService";

    static boolean avRealtimeRunning() {
        return sh("dumpsys activity services " + HYPATIA + " | grep -q MalwareScannerService") == 0;
    }

    /** Antivirus tile: installed?, database age/size, realtime scanner state. */
    static void avStatus(Op op) {
        op.kv("AV_INSTALLED", uidOf(HYPATIA) > 0 ? "1" : "0");
        File db = new File("/data/data/" + HYPATIA + "/files/signatures/hypatia-sha256-bloom.bin");
        op.kv("AV_DB", db.exists() ? new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date(db.lastModified())) : "нет");
        long size = 0;
        File[] fs = new File("/data/data/" + HYPATIA + "/files/signatures").listFiles();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".bin")) size += f.length();
        op.kv("AV_DB_MB", String.valueOf(size >> 20));
        op.kv("AV_REALTIME", avRealtimeRunning() ? "1" : "0");
    }

    static void avRealtime(boolean on) {
        sh(on ? "am start-foreground-service -n " + HYPATIA_RT + " >/dev/null 2>&1"
              : "am stop-service -n " + HYPATIA_RT + " >/dev/null 2>&1");
        sleep(1500);
    }

    /**
     * On-demand scan, remote-friendly. Hypatia only loads its databases into memory from its realtime service or
     * from its own "update databases" menu (Database.initDatabase); a plain scan then says "database unavailable".
     * So: start the service (loads the databases in Hypatia's process), open Hypatia, press its toolbar "scan"
     * button (not reachable with a D-pad) by a tap scaled to the screen, and stop the service again if realtime
     * protection was off (user decision: off by default).
     */
    static void avScan(Op op) {
        if (uidOf(HYPATIA) <= 0) { op.done(false, "Hypatia не установлена"); return; }
        boolean wasOn = avRealtimeRunning();
        if (!wasOn) avRealtime(true);
        // wait until the service has loaded md5, sha1 and sha256 (stopping it earlier cancels the load:
        // JobCancellationException, logged by Hypatia as "error while validating the database")
        String pid = "";
        for (int i = 0; i < 20 && pid.isEmpty(); i++) { pid = shOut("pidof " + HYPATIA).trim(); if (pid.isEmpty()) sleep(500); }
        boolean loaded = false;
        for (int i = 0; i < 120 && !loaded && !pid.isEmpty(); i++) {
            String l = shOut("logcat -d --pid=" + pid + " -s 'Hypatia Logger'");
            loaded = l.contains("Loaded sha256");
            if (!loaded) sleep(1000);
        }
        if (!loaded) op.log("базы Hypatia не загрузились за 2 минуты — проверка может сообщить «база недоступна»");
        sh("am start -n " + HYPATIA + "/us.spotco.malwarescanner.MainActivity >/dev/null 2>&1");
        sleep(3000);
        int w = 1920, h = 1080;
        try {
            String[] p = shOut("wm size").replaceAll(".*: *", "").trim().split("x");
            w = Integer.parseInt(p[0].trim()); h = Integer.parseInt(p[1].trim());
        } catch (Exception ignored) { }
        // toolbar "scan" button: 1805,47 on a 1920x1080 screen
        sh("input tap " + (w * 1805 / 1920) + " " + (h * 47 / 1080));
        if (!wasOn) { sleep(3000); avRealtime(false); }
        op.done(true, "проверка запущена в Hypatia");
    }

    static String shOut(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            String out = new String(readAll(p.getInputStream()), "UTF-8");
            p.waitFor();
            return out;
        } catch (Exception e) { return ""; }
    }

    /** Downloads the signed bloom databases into Hypatia's own dir (Hypatia verifies the GPG signatures itself). */
    static boolean updateHypatia(Op op) throws Exception {
        int uid = uidOf(HYPATIA);
        if (uid <= 0) { if (op != null) op.log("Hypatia не установлена"); return false; }
        String dir = "/data/data/" + HYPATIA + "/files/signatures";
        new File(dir).mkdirs();
        int changed = 0;
        for (String name : HYPATIA_FILES) {
            File f = new File(dir + "/" + name);
            HttpURLConnection c = (HttpURLConnection) new URL(HYPATIA_DB + name).openConnection();
            c.setConnectTimeout(20_000); c.setReadTimeout(60_000);
            if (f.exists()) c.setIfModifiedSince(f.lastModified());
            int code = c.getResponseCode();
            if (code == 304) { c.disconnect(); continue; }
            if (code != 200) throw new Exception(name + ": HTTP " + code);
            File tmp = new File(f.getPath() + ".new");
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(tmp)) {
                byte[] b = new byte[1 << 16];
                for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
            }
            if (!tmp.renameTo(f)) throw new Exception("rename " + name);
            if (c.getLastModified() > 0) f.setLastModified(c.getLastModified());
            changed++;
        }
        sh("chown -R " + uid + ":" + uid + " /data/data/" + HYPATIA + "/files && chmod 600 " + dir + "/* && restorecon -R /data/data/" + HYPATIA + "/files");
        String msg = changed == 0 ? "базы Hypatia актуальны" : "базы Hypatia обновлены (" + changed + " файлов)";
        log(msg);
        if (op != null) op.log(msg);
        // the realtime scanner loads the databases when it starts: restart it only if the user has it running
        if (changed > 0 && sh("dumpsys activity services " + HYPATIA + " | grep -q MalwareScannerService") == 0)
            sh("am stop-service -n " + HYPATIA + "/us.spotco.malwarescanner.MalwareScannerService >/dev/null 2>&1; "
                + "am start-foreground-service -n " + HYPATIA + "/us.spotco.malwarescanner.MalwareScannerService >/dev/null 2>&1");
        return true;
    }

    // ---------------------------------------------------------------- system updates (signed recovery OTA)

    /** Update sources: user list (editable from the app), else the defaults shipped in /vendor. */
    static List<String> updateUrls() {
        String text = new File(URLS_USER).exists() ? read(URLS_USER) : read(URLS_DEFAULT);
        List<String> urls = new ArrayList<>();
        for (String l : text.split("\n")) {
            l = l.trim();
            if (l.startsWith("http://") || l.startsWith("https://")) urls.add(l.endsWith("/") ? l : l + "/");
        }
        return urls;
    }

    static synchronized void editUrls(String url, boolean add) {
        List<String> urls = updateUrls();
        String u = url.endsWith("/") ? url : url + "/";
        urls.remove(u);
        if (add && (u.startsWith("http://") || u.startsWith("https://"))) urls.add(u);
        StringBuilder b = new StringBuilder();
        for (String x : urls) b.append(x).append('\n');
        write(URLS_USER, b.toString());
    }

    /** Lists update packages: removable media (tvbox-update-*.zip) and every source's latest.txt. */
    static void updateCheck(Op op) {
        String cur = prop("ro.tvbox.release");
        op.kv("CURRENT", cur);
        int found = 0;
        for (String root : new String[]{"/mnt/media_rw", "/storage"}) {
            File[] vols = new File(root).listFiles();
            if (vols == null) continue;
            for (File v : vols) {
                if (v.getName().equals("emulated") || v.getName().equals("self")) continue;
                File[] fs = v.listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    String n = f.getName();
                    if (!n.startsWith("tvbox-update-") || !n.endsWith("-" + media() + ".zip")) continue;
                    String ver = n.substring(13, n.length() - 5 - media().length());
                    op.send("UPD file:" + f.getPath() + "\t" + ver + "\t" + "флешка/карта " + v.getName() + "\t" + (f.length() >> 20) + " МБ\t" + (newer(ver, cur) ? "new" : "old"));
                    found++;
                }
            }
            if (found > 0) break;   // the same volume shows up under both roots
        }
        for (String base : updateUrls()) {
            try {
                java.util.Map<String, String> m = latest(base);
                String ver = m.get("version");
                op.send("UPD url:" + base + "\t" + ver + "\t" + base + media() + "/\t" + (Long.parseLong(m.get("size")) >> 20) + " МБ\t" + (newer(ver, cur) ? "new" : "old"));
                if (m.containsKey("notes")) op.send("NOTES url:" + base + "\t" + m.get("notes"));
                found++;
            } catch (Exception e) {
                op.send("UNREACHABLE " + base + "\t" + e.getMessage());
            }
        }
        op.done(true, found == 0 ? "обновлений не найдено" : "найдено: " + found);
    }

    // ---------------------------------------------------------------- system onto the SD card (from the eMMC)

    static final String SD_DIR = "tvbox-sd-image";   // on a USB stick: tvbox-sd-image/{manifest.txt,release.txt,regions/}

    /** SD images: <source>/sd-image/ on update servers, tvbox-sd-image/ on removable media. "SDIMG src\trelease\twhere". */
    static void sdImages(Op op) {
        int found = 0;
        for (String root : new String[]{"/mnt/media_rw", "/storage"}) {
            File[] vols = new File(root).listFiles();
            if (vols == null) continue;
            for (File v : vols) {
                if (v.getName().equals("emulated") || v.getName().equals("self")) continue;
                File d = new File(v, SD_DIR);
                if (!new File(d, "manifest.txt").isFile()) continue;
                op.send("SDIMG file:" + d.getPath() + "\t" + read(d.getPath() + "/release.txt").trim() + "\tфлешка " + v.getName());
                found++;
            }
            if (found > 0) break;
        }
        for (String base : updateUrls()) {
            try {
                String rel = new String(readAll(open("url:" + base + "sd-image", "release.txt")), "UTF-8").trim();
                op.send("SDIMG url:" + base + "\t" + rel + "\t" + base + "sd-image/");
                found++;
            } catch (Exception e) {
                op.send("UNREACHABLE " + base + "\t" + e.getMessage());
            }
        }
        op.done(true, found == 0 ? "образов SD не найдено" : "найдено: " + found);
    }

    /** "url:BASE" -> BASE/sd-image/NAME; "file:DIR" -> DIR/NAME */
    static InputStream open(String src, String name) throws Exception {
        if (src.startsWith("file:")) return new FileInputStream(src.substring(5) + "/" + name);
        String base = src.substring(4);
        if (!base.endsWith("sd-image")) base = base + "sd-image";
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/" + name).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(30000);
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode() + " " + name);
        return c.getInputStream();
    }

    static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[16384];
        try (InputStream i = in) { for (int k; (k = i.read(b)) > 0; ) o.write(b, 0, k); }
        return o.toByteArray();
    }

    /**
     * Writes an SD image (manifest.txt: offset_sector size name md5; regions/NAME.gz) onto the SD card while the
     * system runs from the eMMC: streamed (download/read -> gunzip -> card, md5 of the source on the way), then
     * every region is read back from the card and compared. Same checks as device/sd-write.sh.
     */
    static void installSd(Op op, String src) throws Exception {
        if (bootedFromSd()) { op.done(false, "система загружена с карты SD: на неё саму записывать нельзя"); return; }
        if (!new File("/sys/block/mmcblk0/device").getCanonicalPath().contains("ff500000.dwmmc")) { op.done(false, "карта SD не найдена"); return; }
        if (read("/proc/mounts").contains("mmcblk0")) { op.done(false, "карта SD смонтирована — извлеките её через настройки и вставьте снова"); return; }
        List<String[]> m = new ArrayList<>();
        for (String l : new String(readAll(open(src, "manifest.txt")), "UTF-8").split("\n"))
            if (l.trim().split("\\s+").length == 4) m.add(l.trim().split("\\s+"));
        if (m.isEmpty()) { op.done(false, "пустой manifest.txt"); return; }
        String rel = new String(readAll(open(src, "release.txt")), "UTF-8").trim();
        if (!rel.endsWith(" sd")) { op.done(false, "это образ не для SD: " + rel); return; }
        String[] last = m.get(m.size() - 1);
        long need = Long.parseLong(last[0]) + Long.parseLong(last[1]) / 512;
        long have = Long.parseLong(read("/sys/block/mmcblk0/size").trim());
        if (have != need) { op.done(false, "размер карты " + (have >> 11) + " МБ, образ рассчитан на " + (need >> 11) + " МБ (нужна карта того же объёма)"); return; }
        long all = total(m), done = 0;
        op.log("Шаг 1/2: запись выпуска " + rel + " на карту SD (" + (all >> 20) + " МиБ)");
        byte[] buf = new byte[1 << 20];
        try (RandomAccessFile dev = new RandomAccessFile(SD, "rw")) {
            for (String[] r : m) {
                long off = Long.parseLong(r[0]) * 512, size = Long.parseLong(r[1]);
                op.log("запись " + r[2] + " @" + r[0]);
                MessageDigest d = MessageDigest.getInstance("MD5");
                dev.seek(off);
                long w = 0;
                try (InputStream in = new GZIPInputStream(open(src, "regions/" + r[2] + ".gz"), 1 << 16)) {
                    for (int n; (n = in.read(buf)) > 0; ) {
                        dev.write(buf, 0, n); d.update(buf, 0, n); w += n;
                        if ((w & ((32L << 20) - 1)) < n) op.progress(done + w, all, "запись " + r[2]);
                    }
                }
                if (w != size) { op.done(false, "размер " + r[2] + ": " + w + " вместо " + size); return; }
                if (!hex(d.digest()).equals(r[3])) { op.done(false, "образ повреждён: " + r[2]); return; }
                done += size;
            }
            dev.getFD().sync();
        }
        sh("sync; blockdev --flushbufs " + SD + "; echo 3 > /proc/sys/vm/drop_caches");
        op.log("Шаг 2/2: сверка записанного");
        done = 0;
        for (String[] r : m) {
            long size = Long.parseLong(r[1]);
            String got = md5Device(SD, Long.parseLong(r[0]) * 512, size, op, new long[]{done, all});
            if (!got.equals(r[3])) { op.done(false, "не совпадает на карте: " + r[2]); return; }
            op.log("совпадает " + r[2]);
            done += size;
        }
        op.done(true, "карта SD записана и сверена: выпуск " + rel + " (" + m.size() + " регионов)");
    }

    /** base/latest.txt: key=value lines (version, file, sha256, size, notes). */
    static String media() { return bootedFromSd() ? "sd" : "emmc"; }

    static java.util.Map<String, String> latest(String base) throws Exception {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        HttpURLConnection c = (HttpURLConnection) new URL(base + media() + "/latest.txt").openConnection();
        c.setConnectTimeout(8_000); c.setReadTimeout(15_000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            for (String l; (l = r.readLine()) != null; ) {
                int eq = l.indexOf('=');
                if (eq > 0) m.put(l.substring(0, eq).trim(), l.substring(eq + 1).trim().replace("\\n", "\n"));
            }
        }
        for (String k : new String[]{"version", "file", "sha256", "size"})
            if (!m.containsKey(k)) throw new Exception("latest.txt без " + k);
        return m;
    }

    static boolean newer(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    static int num(String s) { try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (Exception e) { return 0; } }

    /** "file:/path|url:base keep|wipe": fetch, verify (sha256 + OTA signature), then reboot into recovery. */
    static void updateInstall(Op op, String args) throws Exception {
        String[] a = args.split(" ");
        String src = a[0];
        boolean wipe = a.length > 1 && a[1].equals("wipe");
        // keeping user data is switched off for now (user, 2026-10-28; OTA through recovery is in the backlog)
        if (!wipe) { op.done(false, "обновление с сохранением данных временно отключено"); return; }
        new File(OTA_DIR).mkdirs();
        File pkg = new File(OTA_PKG), tmp = new File(OTA_PKG + ".part");
        tmp.delete();
        String wantSha = null;
        if (src.startsWith("file:")) {
            File in = new File(src.substring(5));
            op.log("копирование " + in.getPath());
            copy(new FileInputStream(in), tmp, in.length(), op);
        } else if (src.startsWith("url:")) {
            String base = src.substring(4);
            java.util.Map<String, String> m = latest(base);
            wantSha = m.get("sha256");
            if (new File("/data").getUsableSpace() < Long.parseLong(m.get("size")) + (512L << 20)) { op.done(false, "мало места для загрузки"); return; }
            op.log("загрузка " + base + media() + "/" + m.get("file"));
            HttpURLConnection c = (HttpURLConnection) new URL(base + media() + "/" + m.get("file")).openConnection();
            c.setConnectTimeout(15_000); c.setReadTimeout(60_000);
            copy(c.getInputStream(), tmp, Long.parseLong(m.get("size")), op);
        } else { op.done(false, "неизвестный источник"); return; }
        if (wantSha != null) {
            op.log("проверка контрольной суммы");
            if (!sha256File(tmp).equals(wantSha)) { tmp.delete(); op.done(false, "контрольная сумма не совпадает — файл повреждён"); return; }
        }
        op.log("проверка подписи пакета");
        try {
            android.os.RecoverySystem.verifyPackage(tmp, null, null);
        } catch (Exception e) {
            tmp.delete(); op.done(false, "подпись не прошла проверку: " + e.getMessage()); return;
        }
        String ver, pm;
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(tmp)) {
            ver = new BufferedReader(new InputStreamReader(z.getInputStream(z.getEntry("tvbox/release.txt")), "UTF-8")).readLine().trim();
            java.util.zip.ZipEntry me = z.getEntry("tvbox/media.txt");
            pm = me == null ? "" : new BufferedReader(new InputStreamReader(z.getInputStream(me), "UTF-8")).readLine().trim();
        }
        if (!pm.equals(media())) { tmp.delete(); op.done(false, "пакет для носителя «" + pm + "», а система загружена с " + media()); return; }
        op.log("пакет: выпуск " + ver + (wipe ? ", с очисткой данных" : ", данные сохраняются"));
        long size = tmp.length();
        if (!tmp.renameTo(pkg)) { op.done(false, "не удалось сохранить пакет"); return; }
        sh("chmod 644 " + OTA_PKG);
        // "с очисткой": recovery cannot install and wipe in one run, so update-binary marks misc after a successful
        // install and tvbox-wipecheck.sh clears userdata on the next boot (nothing is wiped if the update fails)
        File wipeReq = new File(WIPE_FLAG);
        new File("/cache/recovery").mkdirs();
        if (wipe) write(wipeReq.getPath(), "1"); else wipeReq.delete();
        // userdata is f2fs: a rename (and a file without fsync) only survives a reboot after a checkpoint. On
        // 2026-10-27 recovery found no update.zip ("Unable to open"): f2fs had rolled forward to an older checkpoint.
        // fsync the directory and sync (= f2fs checkpoint), then re-read the package before arming recovery.
        try (java.nio.channels.FileChannel d = java.nio.channels.FileChannel.open(new File(OTA_DIR).toPath(), java.nio.file.StandardOpenOption.READ)) {
            d.force(true);
        } catch (Exception e) { op.log("fsync каталога: " + e.getMessage()); }
        sh("sync");
        sh("echo 3 > /proc/sys/vm/drop_caches");
        if (!pkg.isFile() || pkg.length() != size) { op.done(false, "пакет не сохранился на диске (" + pkg.length() + " из " + size + " байт)"); return; }
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(pkg)) {
            if (z.getEntry("META-INF/com/google/android/update-binary") == null) throw new Exception("нет update-binary");
        } catch (Exception e) { op.done(false, "пакет после записи не читается: " + e.getMessage()); return; }
        op.log("пакет записан на диск: " + (size >> 20) + " МБ");
        if (!uncrypt(op, OTA_PKG)) return;
        // bootloader_message: the stock misc.img carries it at 0 (recovery) and at 16 KiB (Rockchip u-boot)
        byte[] bcb = new byte[2048];
        put(bcb, 0, "boot-recovery");
        put(bcb, 64, "recovery\n--update_package=@" + BLOCK_MAP + "\n--locale=ru_RU\n");
        try (RandomAccessFile misc = new RandomAccessFile("/dev/block/by-name/misc", "rw")) {
            misc.seek(0); misc.write(bcb);
            misc.seek(0x4000); misc.write(bcb);
            misc.getFD().sync();
        }
        sh("sync");
        op.done(true, "перезагрузка в recovery для установки " + ver);
        sleep(3000);
        sh("setprop sys.powerctl reboot,recovery");
    }

    /**
     * A normal boot means a recovery command in misc was used up (installed or failed). AOSP recovery clears only
     * the copy at 0; Rockchip u-boot reads the one at 16 KiB, so a failed install kept sending the box back to
     * recovery (2026-10-27). Clear both copies when they still say "boot-recovery".
     */
    static void clearStaleBcb() {
        try (RandomAccessFile misc = new RandomAccessFile("/dev/block/by-name/misc", "rw")) {
            byte[] head = new byte[13], zero = new byte[2048];
            for (long off : new long[]{0, 0x4000}) {
                misc.seek(off); misc.readFully(head);
                if (!new String(head, "US-ASCII").equals("boot-recovery")) continue;
                misc.seek(off); misc.write(zero);
                log("misc: снята устаревшая команда recovery @" + off);
            }
            misc.getFD().sync();
        } catch (Exception e) { log("misc: " + e); }
    }

    /** uncrypt service protocol: it streams progress ints 0..100 (anything else = error), the client answers 0. */
    static boolean uncrypt(Op op, String path) throws Exception {
        new File(BLOCK_MAP).delete();
        write(UNCRYPT_FILE, path + "\n");
        sh("setprop ctl.start uncrypt");
        LocalSocket s = null;
        for (int i = 0; i < 30 && s == null; i++) {
            try {
                s = new LocalSocket();
                s.connect(new android.net.LocalSocketAddress("uncrypt", android.net.LocalSocketAddress.Namespace.RESERVED));
            } catch (Exception e) { s = null; sleep(1000); }
        }
        if (s == null) { op.done(false, "служба uncrypt не запустилась"); return false; }
        int st = -1;
        try (java.io.DataInputStream in = new java.io.DataInputStream(s.getInputStream());
             java.io.DataOutputStream out = new java.io.DataOutputStream(s.getOutputStream())) {
            for (int last = -2; ; last = st) {
                st = in.readInt();
                if (st >= 0 && st <= 100) {
                    if (st != last) op.progress(st, 100, "подготовка пакета для recovery (uncrypt)");
                    if (st == 100) { out.writeInt(0); break; }
                } else { out.writeInt(0); break; }
            }
        } finally { s.close(); }
        String map = read(BLOCK_MAP);
        if (st != 100 || map.isEmpty()) { op.done(false, "uncrypt: ошибка " + st + ", карта блоков не создана"); return false; }
        op.log("карта блоков пакета: " + map.split("\n")[0] + ", " + (map.split("\n").length - 3) + " диапазонов");
        sh("sync");
        return true;
    }

    static void put(byte[] b, int off, String s) {
        byte[] v = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(v, 0, b, off, v.length);
    }

    static void copy(InputStream in, File out, long total, Op op) throws Exception {
        byte[] b = new byte[1 << 20];
        long n = 0;
        try (InputStream i = in; FileOutputStream o = new FileOutputStream(out)) {
            for (int k; (k = i.read(b)) > 0; ) {
                o.write(b, 0, k); n += k;
                if ((n & ((32L << 20) - 1)) < k) op.progress(n, total, "получение пакета " + (n >> 20) + " МБ");
            }
            o.getFD().sync();
        }
        if (total > 0 && n != total) throw new Exception("получено " + n + " из " + total + " байт");
    }

    static String sha256File(File f) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        byte[] b = new byte[1 << 20];
        try (InputStream in = new FileInputStream(f)) {
            for (int k; (k = in.read(b)) > 0; ) d.update(b, 0, k);
        }
        return hex(d.digest());
    }

    // ---------------------------------------------------------------- helpers

    static String md5Gz(String path, Op op, long base, long all, String text) throws Exception {
        MessageDigest d = MessageDigest.getInstance("MD5");
        byte[] b = new byte[1 << 20];
        long n = 0;
        try (InputStream in = new GZIPInputStream(new FileInputStream(path), 1 << 16)) {
            for (int k; (k = in.read(b)) > 0; ) {
                d.update(b, 0, k); n += k;
                if (op != null && (n & ((64L << 20) - 1)) < k) op.progress(base + n, all, text);
            }
        }
        return hex(d.digest());
    }

    static String sha256Gz(String path) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        byte[] b = new byte[1 << 20];
        try (InputStream in = new GZIPInputStream(new FileInputStream(path), 1 << 16)) {
            for (int k; (k = in.read(b)) > 0; ) d.update(b, 0, k);
        }
        return hex(d.digest());
    }

    static String md5Device(String dev, long off, long len, Op op, long[] prog) throws Exception {
        return digestDevice("MD5", dev, off, len, op, prog);
    }

    static String sha256Device(String dev, long off, long len) throws Exception {
        return digestDevice("SHA-256", dev, off, len, null, null);
    }

    static String digestDevice(String alg, String dev, long off, long len, Op op, long[] prog) throws Exception {
        MessageDigest d = MessageDigest.getInstance(alg);
        byte[] b = new byte[1 << 20];
        try (RandomAccessFile f = new RandomAccessFile(dev, "r")) {
            f.seek(off);
            for (long left = len, n = 0; left > 0; ) {
                int k = f.read(b, 0, (int) Math.min(b.length, left));
                if (k <= 0) throw new Exception("короткое чтение " + dev);
                d.update(b, 0, k); left -= k; n += k;
                if (op != null && (n & ((64L << 20) - 1)) < k) op.progress(prog[0] + n, prog[1], "сверка");
            }
        }
        return hex(d.digest());
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    static int uidOf(String pkg) {
        try (BufferedReader r = new BufferedReader(new FileReader("/data/system/packages.list"))) {
            for (String l; (l = r.readLine()) != null; ) {
                String[] p = l.split(" ");
                if (p.length > 1 && p[0].equals(pkg)) return Integer.parseInt(p[1]);
            }
        } catch (Exception ignored) { }
        return -1;
    }

    static String prop(String k) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            return (String) c.getMethod("get", String.class).invoke(null, k);
        } catch (Throwable t) { return ""; }
    }

    static String read(String path) {
        StringBuilder s = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            for (String l; (l = r.readLine()) != null; ) s.append(l).append('\n');
        } catch (Exception ignored) { }
        return s.toString();
    }

    static void write(String path, String text) {
        try (FileOutputStream o = new FileOutputStream(path)) { o.write(text.getBytes("UTF-8")); }
        catch (Exception ignored) { }
    }

    static int sh(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            return p.waitFor();
        } catch (Exception e) { return -1; }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    static String now() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()); }

    static synchronized void log(String s) {
        try (FileOutputStream o = new FileOutputStream(STATE + "/tvboxd.log", true)) {
            o.write((now() + " " + s + "\n").getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }
}
