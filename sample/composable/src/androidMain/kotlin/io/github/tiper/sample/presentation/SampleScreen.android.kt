package io.github.tiper.sample.presentation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

@Composable
fun SampleScreenAndroid() {
    Text(
        text = stringResource(R.string.composable_string_android)
    )
}