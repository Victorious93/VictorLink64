// Classic Android check: proves a 32-bit ARM library can load. Built for armeabi-v7a only.
#include <jni.h>

JNIEXPORT jstring JNICALL
Java_org_victoriousframework_victorlink64_classic_Classic32Service_nativeHello(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, "Hello from 32-bit ARM code (armeabi-v7a)");
}
