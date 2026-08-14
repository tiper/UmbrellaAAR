package io.github.tiper.sample.presentation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource

@Composable
fun SampleScreenCommon() {
    Text(
        text = stringResource(Res.string.composable_string),
    )
}
