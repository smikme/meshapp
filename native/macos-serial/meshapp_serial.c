#include "meshapp_serial.h"

#include <errno.h>
#include <sys/ioctl.h>

#if defined(__GNUC__)
#define MESHSERIAL_EXPORT __attribute__((visibility("default")))
#else
#define MESHSERIAL_EXPORT
#endif

MESHSERIAL_EXPORT int meshserial_set_modem_bits(int fd, int bits) {
    return ioctl(fd, TIOCMBIS, &bits) == 0 ? 0 : -errno;
}

MESHSERIAL_EXPORT int meshserial_clear_modem_bits(int fd, int bits) {
    return ioctl(fd, TIOCMBIC, &bits) == 0 ? 0 : -errno;
}

MESHSERIAL_EXPORT int meshserial_get_modem_bits(int fd, int *bits) {
    if (bits == NULL) {
        return -EINVAL;
    }
    return ioctl(fd, TIOCMGET, bits) == 0 ? 0 : -errno;
}
