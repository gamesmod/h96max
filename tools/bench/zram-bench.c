/* Compare tmpfs RAM I/O and an isolated inactive zram device. No swap/production devices. */
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
static double stamp(clockid_t id) { struct timespec t; clock_gettime(id,&t); return t.tv_sec+t.tv_nsec/1e9; }
static void transfer(int fd, unsigned char *buf, size_t n, int writing) {
    size_t at=0;
    while(at<n) {
        ssize_t k=writing?write(fd,buf+at,n-at):read(fd,buf+at,n-at);
        if(k<0 && errno==EINTR) continue;
        if(k<=0) { perror(writing?"write":"read"); exit(2); }
        at+=(size_t)k;
    }
}
int main(int argc,char **argv) {
    if(argc!=5) { fprintf(stderr,"usage: zram-bench PATH MB ROUNDS mixed|random|repeated\n"); return 2; }
    size_t mb=strtoul(argv[2],0,10); int rounds=atoi(argv[3]);
    if(!mb || mb>512 || rounds<1 || rounds>8) return 2;
    const size_t chunk=4096;
    unsigned char *data=malloc(1<<20), *readback=malloc(chunk);
    if(!data || !readback) return 2;
    uint32_t state=1234567;
    for(size_t i=0;i<(1<<20);i++) {
        state^=state<<13; state^=state>>17; state^=state<<5;
        int random=!strcmp(argv[4],"random") || (!strcmp(argv[4],"mixed") && (i/4096)%4==0);
        data[i]=random?(unsigned char)state:(unsigned char)((i%67)+(i/4096)%29+32);
    }
    int fd=open(argv[1],O_RDWR|O_CREAT,0600); if(fd<0){perror("open");return 2;}
    double wall0=stamp(CLOCK_MONOTONIC), cpu0=stamp(CLOCK_PROCESS_CPUTIME_ID);
    for(int r=0;r<rounds;r++) {
        if(lseek(fd,0,SEEK_SET)<0) return 2;
        for(size_t off=0;off<mb*(1<<20);off+=chunk) transfer(fd,data+off%(1<<20),chunk,1);
    }
    double wwall=stamp(CLOCK_MONOTONIC)-wall0, wcpu=stamp(CLOCK_PROCESS_CPUTIME_ID)-cpu0;
    wall0=stamp(CLOCK_MONOTONIC); cpu0=stamp(CLOCK_PROCESS_CPUTIME_ID);
    for(int r=0;r<rounds;r++) {
        if(lseek(fd,0,SEEK_SET)<0) return 2;
        for(size_t off=0;off<mb*(1<<20);off+=chunk) {
            transfer(fd,readback,chunk,0);
            if(memcmp(readback,data+off%(1<<20),chunk)) {fprintf(stderr,"data mismatch at %zu\n",off);return 3;}
        }
    }
    double rwall=stamp(CLOCK_MONOTONIC)-wall0, rcpu=stamp(CLOCK_PROCESS_CPUTIME_ID)-cpu0;
    printf("%s %s MB=%zu rounds=%d write_wall=%.4f write_cpu=%.4f write_MBps=%.1f read_wall=%.4f read_cpu=%.4f read_MBps=%.1f verified=1\n",
        argv[1],argv[4],mb,rounds,wwall,wcpu,mb*rounds/wwall,rwall,rcpu,mb*rounds/rwall);
    close(fd); free(data); free(readback); return 0;
}
