#define _GNU_SOURCE
#include <stdio.h>
#include <stddef.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <dirent.h>
#include <fcntl.h>
#define O(t, f) printf("%-24s %-12s off %3zu size %zu\n", #t, #f, offsetof(struct t, f), sizeof(((struct t *)0)->f))
int main(void) {
  printf("sizeof stat %zu, statvfs %zu, dirent %zu, flock %zu\n", sizeof(struct stat), sizeof(struct statvfs), sizeof(struct dirent), sizeof(struct flock));
  O(stat, st_dev); O(stat, st_ino); O(stat, st_mode); O(stat, st_nlink); O(stat, st_uid); O(stat, st_gid);
  O(stat, st_rdev); O(stat, st_size); O(stat, st_blksize); O(stat, st_blocks);
  O(stat, st_atim.tv_sec); O(stat, st_atim.tv_nsec); O(stat, st_mtim.tv_sec); O(stat, st_mtim.tv_nsec); O(stat, st_ctim.tv_sec); O(stat, st_ctim.tv_nsec);
  O(statvfs, f_bsize); O(statvfs, f_frsize); O(statvfs, f_blocks); O(statvfs, f_bfree); O(statvfs, f_bavail);
  O(dirent, d_ino); O(dirent, d_off); O(dirent, d_reclen); O(dirent, d_type); O(dirent, d_name);
  O(flock, l_type); O(flock, l_whence); O(flock, l_start); O(flock, l_len); O(flock, l_pid);
  printf("F_SETLK %d F_SETLKW %d F_GETLK %d F_RDLCK %d F_WRLCK %d F_UNLCK %d UTIME_OMIT 0x%lx\n", F_SETLK, F_SETLKW, F_GETLK, F_RDLCK, F_WRLCK, F_UNLCK, (long)UTIME_OMIT);
  return 0;
}
