package io.github.tiper.sample.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import io.github.tiper.sample.jni1.NativeLib as NativeLib1

class SampleViewModel(): ViewModel() {
    init {
        val result1 = NativeLib1.addNumbers(5, 7)
        Log.d("Native", "SAMPLE: Result of 5 + 7 = $result1")
    }
}
