package com.arceusvault.keel.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.arceusvault.keel.R

/** Keel's sailboat mark on its rounded tile. */
@Composable
fun KeelMark(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.keel_mark), contentDescription = null, modifier = modifier)
}
