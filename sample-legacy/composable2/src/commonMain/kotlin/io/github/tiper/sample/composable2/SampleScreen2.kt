package io.github.tiper.sample.composable2

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource

@Composable
fun SampleScreen2Common() {
    Text(
        text = stringResource(Res.string.composable_string2),
    )
}
