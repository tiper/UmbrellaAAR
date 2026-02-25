package io.github.tiper.sample.jni1

object NativeLib {
    init {
        System.loadLibrary("native-lib1")
    }

    external fun addNumbers(a: Int, b: Int): Int
}
