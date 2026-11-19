package com.clipforge.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clipforge.R

@Composable
fun LogoMark(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_logo_mark), contentDescription = null, modifier = modifier.size(size))
}

/** Mark + "ClipForge" wordmark in Outfit, as in the logo. */
@Composable
fun BrandLockup(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LogoMark(28.dp)
        Spacer(Modifier.width(10.dp))
        Text("ClipForge", style = MaterialTheme.typography.titleLarge)
    }
}
