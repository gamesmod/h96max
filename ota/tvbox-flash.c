/*
 * tvbox-flash: OTA helper for the rk3318-android recovery (static aarch64).
 *   tvbox-flash DEVICE SIZE MD5 < image
 *   tvbox-flash --check DEVICE SIZE      (exit 0 if DEVICE can hold SIZE bytes)
 *   tvbox-flash --dm-map MAP NAME NODE   (uncrypt block map -> device-mapper linear device, mknod at NODE;
 *                                         recovery has no dmctl, and the package on /data is in several pieces)
 *   tvbox-flash --zip-cat FILE SIZE NAME (stdout = STORED zip entry NAME of the zip in the first SIZE bytes of
 *                                         FILE; the recovery ziptool refuses block devices)
 * Writes exactly SIZE bytes from stdin to DEVICE at offset 0, fsyncs, drops caches, reads them back and
 * compares the MD5. Progress "progress <0..1>" lines go to stdout. Exit 0 = written and verified.
 * 64-bit sizes everywhere (the recovery shell is mksh: 32-bit arithmetic).
 */
#define _FILE_OFFSET_BITS 64
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <linux/fs.h>
#include <linux/dm-ioctl.h>
#include <sys/sysmacros.h>

/* ---- MD5 (RFC 1321, compact public-domain style implementation) ---- */
typedef struct { uint32_t a, b, c, d; uint64_t n; uint8_t buf[64]; } md5_t;
static const uint32_t K[64] = {
 0xd76aa478,0xe8c7b756,0x242070db,0xc1bdceee,0xf57c0faf,0x4787c62a,0xa8304613,0xfd469501,0x698098d8,0x8b44f7af,0xffff5bb1,0x895cd7be,
 0x6b901122,0xfd987193,0xa679438e,0x49b40821,0xf61e2562,0xc040b340,0x265e5a51,0xe9b6c7aa,0xd62f105d,0x02441453,0xd8a1e681,0xe7d3fbc8,
 0x21e1cde6,0xc33707d6,0xf4d50d87,0x455a14ed,0xa9e3e905,0xfcefa3f8,0x676f02d9,0x8d2a4c8a,0xfffa3942,0x8771f681,0x6d9d6122,0xfde5380c,
 0xa4beea44,0x4bdecfa9,0xf6bb4b60,0xbebfbc70,0x289b7ec6,0xeaa127fa,0xd4ef3085,0x04881d05,0xd9d4d039,0xe6db99e5,0x1fa27cf8,0xc4ac5665,
 0xf4292244,0x432aff97,0xab9423a7,0xfc93a039,0x655b59c3,0x8f0ccc92,0xffeff47d,0x85845dd1,0x6fa87e4f,0xfe2ce6e0,0xa3014314,0x4e0811a1,
 0xf7537e82,0xbd3af235,0x2ad7d2bb,0xeb86d391};
static const uint8_t R[64] = {7,12,17,22,7,12,17,22,7,12,17,22,7,12,17,22,5,9,14,20,5,9,14,20,5,9,14,20,5,9,14,20,
 4,11,16,23,4,11,16,23,4,11,16,23,4,11,16,23,6,10,15,21,6,10,15,21,6,10,15,21,6,10,15,21};
#define ROL(x, c) (((x) << (c)) | ((x) >> (32 - (c))))
static void md5_block(md5_t *m, const uint8_t *p) {
    uint32_t w[16], a = m->a, b = m->b, c = m->c, d = m->d;
    for (int i = 0; i < 16; i++) w[i] = p[i*4] | p[i*4+1] << 8 | p[i*4+2] << 16 | (uint32_t)p[i*4+3] << 24;
    for (int i = 0; i < 64; i++) {
        uint32_t f; int g;
        if (i < 16) { f = (b & c) | (~b & d); g = i; }
        else if (i < 32) { f = (d & b) | (~d & c); g = (5*i + 1) % 16; }
        else if (i < 48) { f = b ^ c ^ d; g = (3*i + 5) % 16; }
        else { f = c ^ (b | ~d); g = (7*i) % 16; }
        uint32_t t = d; d = c; c = b; b = b + ROL(a + f + K[i] + w[g], R[i]); a = t;
    }
    m->a += a; m->b += b; m->c += c; m->d += d;
}
static void md5_init(md5_t *m) { m->a = 0x67452301; m->b = 0xefcdab89; m->c = 0x98badcfe; m->d = 0x10325476; m->n = 0; }
static void md5_update(md5_t *m, const uint8_t *p, size_t len) {
    size_t have = m->n % 64; m->n += len;
    if (have) { size_t k = 64 - have < len ? 64 - have : len; memcpy(m->buf + have, p, k); p += k; len -= k; if (have + k < 64) return; md5_block(m, m->buf); }
    for (; len >= 64; p += 64, len -= 64) md5_block(m, p);
    memcpy(m->buf, p, len);
}
static void md5_hex(md5_t *m, char out[33]) {
    uint64_t bits = m->n * 8; uint8_t pad = 0x80, z = 0, l[8];
    md5_update(m, &pad, 1);
    while (m->n % 64 != 56) md5_update(m, &z, 1);
    for (int i = 0; i < 8; i++) l[i] = bits >> (8 * i);
    md5_update(m, l, 8);
    uint32_t v[4] = {m->a, m->b, m->c, m->d};
    for (int i = 0; i < 16; i++) sprintf(out + 2*i, "%02x", (v[i/4] >> (8 * (i % 4))) & 0xff);
}

static char buf[1 << 20];

/* capacity of a block device (BLKGETSIZE64) or of a regular file (tests) */
static int capacity(int fd, uint64_t *cap) {
    struct stat st;
    if (fstat(fd, &st)) return -1;
    if (S_ISBLK(st.st_mode)) return ioctl(fd, BLKGETSIZE64, cap);
    *cap = (uint64_t)st.st_size;
    return 0;
}


/* uncrypt block map: line 1 block device, line 2 "size blksize", line 3 range count, then "start end" (end exclusive) */
static int dm_map(const char *map, const char *name, const char *node) {
    FILE *f = fopen(map, "r");
    if (!f) { perror(map); return 1; }
    char bdev[256]; unsigned long long fsize, bs; int n;
    if (fscanf(f, "%255s %llu %llu %d", bdev, &fsize, &bs, &n) != 4 || n < 1 || n > 4096 || bs % 512) { fprintf(stderr, "bad map\n"); return 1; }
    struct stat st;
    if (stat(bdev, &st) || !S_ISBLK(st.st_mode)) { perror(bdev); return 1; }
    size_t sz = sizeof(struct dm_ioctl) + (size_t)n * (sizeof(struct dm_target_spec) + 64) + 64;
    char *buf = calloc(1, sz);
    struct dm_ioctl *io = (struct dm_ioctl *)buf;
    int fd = open("/dev/device-mapper", O_RDWR);
    if (fd < 0) { perror("/dev/device-mapper"); return 1; }
#define DMINIT(fl) do { memset(io, 0, sizeof(*io)); io->version[0] = 4; io->data_size = sz; \
        io->data_start = sizeof(struct dm_ioctl); io->flags = (fl); strncpy(io->name, name, DM_NAME_LEN - 1); } while (0)
    DMINIT(0);
    ioctl(fd, DM_DEV_REMOVE, io);                       /* stale device from an earlier run */
    DMINIT(0);
    if (ioctl(fd, DM_DEV_CREATE, io)) { perror("DM_DEV_CREATE"); return 1; }
    DMINIT(0);
    io->target_count = n;
    char *p = buf + sizeof(struct dm_ioctl);
    unsigned long long sector = 0, total = 0;
    for (int i = 0; i < n; i++) {
        unsigned long long a, b;
        if (fscanf(f, "%llu %llu", &a, &b) != 2 || b <= a) { fprintf(stderr, "bad range %d\n", i); return 1; }
        struct dm_target_spec *t = (struct dm_target_spec *)p;
        t->sector_start = sector;
        t->length = (b - a) * bs / 512;
        strcpy(t->target_type, "linear");
        char *params = p + sizeof(*t);
        int len = sprintf(params, "%u:%u %llu", major(st.st_rdev), minor(st.st_rdev), a * bs / 512) + 1;
        size_t step = (sizeof(*t) + len + 7) & ~(size_t)7;
        t->next = step;
        p += step; sector += t->length; total += (b - a) * bs;
    }
    fclose(f);
    if (total < fsize) { fprintf(stderr, "map covers %llu < %llu bytes\n", total, fsize); return 1; }
    if (ioctl(fd, DM_TABLE_LOAD, io)) { perror("DM_TABLE_LOAD"); return 1; }
    DMINIT(0);
    if (ioctl(fd, DM_DEV_SUSPEND, io)) { perror("DM_DEV_SUSPEND(resume)"); return 1; }
    dev_t dev = io->dev;
    unlink(node);
    if (mknod(node, S_IFBLK | 0400, dev)) { perror(node); return 1; }
    printf("%s %u:%u %llu\n", node, major(dev), minor(dev), fsize);
    return 0;
}


static uint32_t le32(const unsigned char *p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24; }
static uint16_t le16(const unsigned char *p) { return p[0] | p[1] << 8; }

static int zip_cat(const char *file, uint64_t size, const char *name) {
    int fd = open(file, O_RDONLY);
    if (fd < 0) { perror(file); return 1; }
    /* end of central directory: last 22..(22 + 65535) bytes */
    size_t tail = size < 66000 ? size : 66000;
    unsigned char *t = malloc(tail);
    if (pread(fd, t, tail, size - tail) != (ssize_t)tail) { perror("pread eocd"); return 1; }
    long e = -1;
    for (long i = (long)tail - 22; i >= 0; i--) if (le32(t + i) == 0x06054b50) { e = i; break; }
    if (e < 0) { fprintf(stderr, "no zip end record\n"); return 1; }
    uint32_t cd_size = le32(t + e + 12), cd_off = le32(t + e + 16);
    unsigned entries = le16(t + e + 10);
    unsigned char *cd = malloc(cd_size);
    if (pread(fd, cd, cd_size, cd_off) != (ssize_t)cd_size) { perror("pread cd"); return 1; }
    size_t nl = strlen(name);
    for (unsigned i = 0, o = 0; i < entries && o + 46 <= cd_size; i++) {
        if (le32(cd + o) != 0x02014b50) break;
        unsigned n = le16(cd + o + 28), x = le16(cd + o + 30), c = le16(cd + o + 32);
        if (n == nl && !memcmp(cd + o + 46, name, n)) {
            if (le16(cd + o + 10) != 0) { fprintf(stderr, "%s is compressed\n", name); return 1; }
            uint32_t len = le32(cd + o + 20), lho = le32(cd + o + 42);
            unsigned char lh[30];
            if (pread(fd, lh, 30, lho) != 30 || le32(lh) != 0x04034b50) { fprintf(stderr, "bad local header\n"); return 1; }
            off_t pos = (off_t)lho + 30 + le16(lh + 26) + le16(lh + 28);
            static char b[1 << 20];
            while (len > 0) {
                ssize_t k = pread(fd, b, len < sizeof b ? len : sizeof b, pos);
                if (k <= 0) { perror("pread data"); return 1; }
                if (write(1, b, k) != k) return 1;
                len -= k; pos += k;
            }
            return 0;
        }
        o += 46 + n + x + c;
    }
    fprintf(stderr, "%s not in zip\n", name);
    return 1;
}

int main(int argc, char **argv) {
    if (argc == 5 && !strcmp(argv[1], "--zip-cat")) return zip_cat(argv[2], strtoull(argv[3], NULL, 10), argv[4]);
    if (argc == 5 && !strcmp(argv[1], "--dm-map")) return dm_map(argv[2], argv[3], argv[4]);
    if (argc == 4 && !strcmp(argv[1], "--check")) {
        uint64_t cap = 0, size = strtoull(argv[3], NULL, 10);
        int fd = open(argv[2], O_RDONLY);
        if (fd < 0 || capacity(fd, &cap)) { perror(argv[2]); return 1; }
        if (cap < size) { fprintf(stderr, "%s: %llu bytes < image %llu\n", argv[2], (unsigned long long)cap, (unsigned long long)size); return 1; }
        return 0;
    }
    if (argc != 4) { fprintf(stderr, "usage: tvbox-flash DEVICE SIZE MD5 < image | --check DEVICE SIZE | --dm-map MAP NAME NODE | --zip-cat FILE SIZE NAME\n"); return 2; }
    const char *dev = argv[1]; uint64_t size = strtoull(argv[2], NULL, 10); const char *want = argv[3];
    int fd = open(dev, O_RDWR);
    if (fd < 0) { perror(dev); return 1; }
    uint64_t cap = 0;
    if (capacity(fd, &cap) || cap < size) { fprintf(stderr, "%s too small: %llu < %llu\n", dev, (unsigned long long)cap, (unsigned long long)size); return 1; }
    uint64_t done = 0, step = size / 50 + 1, next = step;
    while (done < size) {
        size_t want_n = size - done < sizeof buf ? (size_t)(size - done) : sizeof buf, got = 0;
        while (got < want_n) { ssize_t r = read(0, buf + got, want_n - got); if (r <= 0) break; got += r; }
        if (got == 0) break;
        for (size_t off = 0; off < got; ) { ssize_t w = write(fd, buf + off, got - off); if (w <= 0) { perror("write"); return 1; } off += w; }
        done += got;
        if (done >= next) { printf("progress %.3f\n", (double)done / size * 0.5); fflush(stdout); next += step; }
    }
    if (done != size) { fprintf(stderr, "short input: %llu of %llu\n", (unsigned long long)done, (unsigned long long)size); return 1; }
    if (fsync(fd)) { perror("fsync"); return 1; }
    close(fd);
    int dc = open("/proc/sys/vm/drop_caches", O_WRONLY); if (dc >= 0) { if (write(dc, "3", 1) != 1) { /* best effort */ } close(dc); }
    fd = open(dev, O_RDONLY);
    md5_t m; md5_init(&m); done = 0; next = step;
    while (done < size) {
        size_t n = size - done < sizeof buf ? (size_t)(size - done) : sizeof buf;
        ssize_t r = read(fd, buf, n);
        if (r <= 0) { fprintf(stderr, "readback failed at %llu\n", (unsigned long long)done); return 1; }
        md5_update(&m, (uint8_t *)buf, r); done += r;
        if (done >= next) { printf("progress %.3f\n", 0.5 + (double)done / size * 0.5); fflush(stdout); next += step; }
    }
    close(fd);
    char hex[33]; md5_hex(&m, hex);
    if (strcmp(hex, want)) { fprintf(stderr, "verify FAILED %s: %s != %s\n", dev, hex, want); return 1; }
    printf("verified %s %s\n", dev, hex);
    return 0;
}
