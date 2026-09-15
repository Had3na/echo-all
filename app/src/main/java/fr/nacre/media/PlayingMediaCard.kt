package fr.nacre.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Playing-card silhouette without suits; wide = long horizontal rectangle with the picture on the left. */
@Composable
fun PlayingMediaCard(title: String, subtitle: String, wide: Boolean, compact: Boolean, onClick: () -> Unit, art: @Composable BoxScope.() -> Unit) {
    val size = if (wide) Modifier.width(if (compact) 250.dp else 300.dp).height(if (compact) 76.dp else 96.dp)
        else Modifier.width(if (compact) 144.dp else 174.dp).aspectRatio(.68f)
    Surface(size.clickable(onClick = onClick), shape = RoundedCornerShape(if (wide) 16.dp else 18.dp), color = Panel,
        border = BorderStroke(1.dp, Lime.copy(alpha = .45f)), shadowElevation = 4.dp) {
        val background = Modifier.background(Brush.verticalGradient(listOf(Lime.copy(alpha = .12f), Panel)))
        if (wide) Row(background.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Ink.copy(alpha = .5f)), contentAlignment = Alignment.Center, content = art)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = Muted)
            }
        } else Column(background.padding(12.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(Ink.copy(alpha = .5f)), contentAlignment = Alignment.Center, content = art)
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = Muted)
        }
    }
}
