#pragma once

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Выставляет указанные modem bits через ioctl(TIOCMBIS).
 * Возвращает 0 при успехе или -errno при ошибке.
 */
int meshserial_set_modem_bits(int fd, int bits);

/**
 * Сбрасывает указанные modem bits через ioctl(TIOCMBIC).
 * Возвращает 0 при успехе или -errno при ошибке.
 */
int meshserial_clear_modem_bits(int fd, int bits);

/**
 * Читает текущее состояние modem bits через ioctl(TIOCMGET).
 * Возвращает 0 при успехе или -errno при ошибке.
 */
int meshserial_get_modem_bits(int fd, int *bits);

#ifdef __cplusplus
}
#endif
