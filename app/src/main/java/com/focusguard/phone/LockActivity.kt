package com.focusguard.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FocusTheme {
                BackHandler(enabled = true) { /* no way out until the timer ends */ }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LockScreen(onDone = { finish() })
                }
            }
        }
    }
}

@Composable
fun LockScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val remMs = (Session.endAt(ctx) - now).coerceAtLeast(0)
    val finished = remMs <= 0
    LaunchedEffect(finished) {
        if (finished) { Session.complete(ctx); onDone() }
    }
    val totalMs = (Session.totalMin(ctx) * 60_000L).coerceAtLeast(1)
    val appItems = remember { Apps.launchItems(ctx) }
    val s = remMs / 1000
    val timeText = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    else "%02d:%02d".format(s / 60, s % 60)

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // top: clock
        Text(SimpleDateFormat("hh:mm", Locale.getDefault()).format(Date(now)),
            fontSize = 64.sp, fontWeight = FontWeight.Light)
        Text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(now)),
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        // middle: countdown ring
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Ring(remMs.toFloat() / totalMs, Modifier.fillMaxWidth(0.8f).aspectRatio(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(timeText, fontSize = 48.sp, fontWeight = FontWeight.Light)
                Text("focus time left", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // bottom: allowed apps
        Text("Allowed", color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.heightIn(max = 220.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(appItems) { app ->
                Column(
                    Modifier.clickable { runCatching { ctx.startActivity(Intent(app.intent)) } },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val bmp = remember(app.pkg) { app.icon?.toBitmap(96, 96)?.asImageBitmap() }
                    if (bmp != null) Image(bmp, app.label, Modifier.size(52.dp))
                    Text(app.label, fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun Ring(fraction: Float, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        val w = size.minDimension * 0.07f
        val r = size.minDimension / 2 - w
        val c = center
        drawCircle(track, r, c, style = Stroke(w))
        drawArc(primary, -90f, 360f * fraction.coerceIn(0f, 1f), false,
            Offset(c.x - r, c.y - r), Size(2 * r, 2 * r),
            style = Stroke(w, cap = StrokeCap.Round))
    }
}
