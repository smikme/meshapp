#pragma once

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Sets the requested modem bits through ioctl(TIOCMBIS).
 * Returns 0 on success or -errno on failure.
 */
int meshserial_set_modem_bits(int fd, int bits);

/**
 * Clears the requested modem bits through ioctl(TIOCMBIC).
 * Returns 0 on success or -errno on failure.
 */
int meshserial_clear_modem_bits(int fd, int bits);

/**
 * Reads the current modem-bit state through ioctl(TIOCMGET).
 * Returns 0 on success or -errno on failure.
 */
int meshserial_get_modem_bits(int fd, int *bits);

#ifdef __cplusplus
}
#endif
