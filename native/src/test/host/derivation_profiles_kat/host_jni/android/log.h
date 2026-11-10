/* Host stand-in for <android/log.h>: the priorities the bridge's LOG macros name, and the print call the
 * KAT defines. */
#ifndef DERIVATION_PROFILES_KAT_HOST_ANDROID_LOG_H
#define DERIVATION_PROFILES_KAT_HOST_ANDROID_LOG_H
enum { ANDROID_LOG_DEBUG = 3, ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };
int __android_log_print(int prio, const char *tag, const char *fmt, ...);
#endif
