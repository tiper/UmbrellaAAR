package io.github.tiper.sample.jni2

object NativeLib {
    init {
        System.loadLibrary("native-lib2")
    }

    external fun subtractNumbers(a: Int, b: Int): Int
}
