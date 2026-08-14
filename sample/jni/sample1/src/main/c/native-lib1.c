#include <jni.h>

JNIEXPORT jint JNICALL
Java_io_github_tiper_sample_jni1_NativeLib_addNumbers(JNIEnv *env, jobject thiz, jint a, jint b) {
    return a + b;
}
