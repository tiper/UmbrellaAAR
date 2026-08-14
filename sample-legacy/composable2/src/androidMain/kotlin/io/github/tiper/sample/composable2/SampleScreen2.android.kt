package io.github.tiper.sample.composable2

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

@Composable
fun SampleScreen2Android() {
    Text(
        text = stringResource(R.string.composable_string_android2),
    )
}
