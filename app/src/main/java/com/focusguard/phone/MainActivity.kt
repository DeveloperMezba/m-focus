package com.focusguard.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FocusTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Home(refresh) {
                        startActivity(Intent(this, LockActivity::class.java))
                        finish()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Session.isActive(this)) {
            startActivity(Intent(this, LockActivity::class.java))
            finish()
        }
        refresh++
    }
}

fun a11yEnabled(c: Context): Boolean {
    val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    val me = ComponentName(c, GuardService::class.java)
    return enabled.split(':').any {
        it.equals(me.flattenToString(), true) || it.equals(me.flattenToShortString(), true)
    }
}

fun batteryOk(c: Context): Boolean =
    c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

@Composable
fun Home(refresh: Int, onStarted: () -> Unit) {
    val ctx = LocalContext.current
    var minutes by remember { mutableIntStateOf(50) }
    var showPicker by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val a11y = remember(refresh) { a11yEnabled(ctx) }
    val battery = remember(refresh) { batteryOk(ctx) }
    val notif = remember(refresh) { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
    val extras = remember(refresh, showPicker) { Apps.effectiveExtras(ctx).size }
    val today = remember(refresh) { Session.todayMinutes(ctx) }
    val ready = a11y && notif && battery

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("M Focus", fontSize = 26.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${today / 60} h ${today % 60} min today", color = MaterialTheme.colorScheme.primary)
        }

        if (!a11y) SetupRow(
            "1. Turn on accessibility",
            "Lets M Focus see which app is open so it can close blocked apps. It does not read your screen.",
            "Open settings",
        ) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        if (!notif) SetupRow(
            "2. Allow notification access",
            "Lets M Focus hide WhatsApp, Messenger, Facebook, Instagram and other social notifications during a session.",
            "Open settings",
        ) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

        if (!battery) SetupRow(
            "3. Disable battery optimization",
            "Stops Android from putting M Focus to sleep during a session.",
            "Allow",
        ) {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            )
        }

        Dial(minutes, { minutes = it }, Modifier.fillMaxWidth(0.8f).align(Alignment.CenterHorizontally))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(60 to "1 h", 180 to "3 h", 360 to "6 h", 420 to "7 h").forEach { (m, label) ->
                FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(label) })
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Always allowed", fontWeight = FontWeight.Medium)
                Text("Phone calls and AI apps (Gemini, ChatGPT, Claude and more). Social media, messengers and browsers can never be added.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Extra apps: $extras", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ showPicker = true }) { Text("Choose extra apps") }
            }
        }

        Button(
            onClick = { confirm = true },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(if (ready) "Start focus session" else "Finish setup first", fontSize = 16.sp) }
    }

    if (showPicker) AppPicker { showPicker = false }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Start $minutes minutes?") },
        text = { Text("Only calls and allowed apps will work. You cannot stop the session early.") },
        confirmButton = {
            TextButton({ confirm = false; Session.start(ctx, minutes); NotificationBlocker.instance?.sweep(); onStarted() }) { Text("Start") }
        },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}

@Composable
fun SetupRow(title: String, desc: String, button: String, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(desc, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick) { Text(button) }
        }
    }
}

const val MAX_MIN = 480 // 8 hours

/** Clock-style dial: turn it like a minute hand. One full turn = 60 minutes, every minute is selectable. */
@Composable
fun Dial(minutes: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tickMajor = MaterialTheme.colorScheme.onSurfaceVariant
    val tickMinor = MaterialTheme.colorScheme.outline
    val current by rememberUpdatedState(minutes)
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                var last = 0.0
                var acc = 0.0
                fun angle(p: Offset): Double {
                    val d = p - Offset(size.width / 2f, size.height / 2f)
                    var a = Math.toDegrees(atan2(d.x.toDouble(), -d.y.toDouble()))
                    if (a < 0) a += 360.0
                    return a
                }
                detectDragGestures(onDragStart = { last = angle(it); acc = current.toDouble() }) { change, _ ->
                    val a = angle(change.position)
                    val delta = (a - last + 540.0) % 360.0 - 180.0   // shortest signed turn
                    last = a
                    acc = (acc + delta / 6.0).coerceIn(1.0, MAX_MIN.toDouble())  // 6 degrees = 1 minute
                    onChange(Math.round(acc).toInt())
                }
            }
        ) {
            val w = size.minDimension * 0.09f
            val r = size.minDimension / 2 - w * 1.8f
            val c = center
            for (i in 0 until 60) {
                val t = Math.toRadians(i * 6.0 - 90)
                val major = i % 5 == 0
                val r1 = r + w * 0.9f
                val r2 = r + w * (if (major) 1.7f else 1.4f)
                drawLine(
                    if (major) tickMajor else tickMinor,
                    Offset(c.x + r1 * cos(t).toFloat(), c.y + r1 * sin(t).toFloat()),
                    Offset(c.x + r2 * cos(t).toFloat(), c.y + r2 * sin(t).toFloat()),
                    strokeWidth = if (major) 4f else 2f,
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(track, r, c, style = Stroke(w))
            val sweep = if (minutes > 0 && minutes % 60 == 0) 360f else (minutes % 60) / 60f * 360f
            drawArc(primary, -90f, sweep, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r),
                style = Stroke(w, cap = StrokeCap.Round))
            val ang = Math.toRadians((sweep - 90f).toDouble())
            drawCircle(primary, w * 0.9f,
                Offset(c.x + r * cos(ang).toFloat(), c.y + r * sin(ang).toFloat()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (minutes < 60) "$minutes" else "%d:%02d".format(minutes / 60, minutes % 60),
                fontSize = 60.sp, fontWeight = FontWeight.Light,
            )
            Text(if (minutes < 60) "minutes" else "hours : minutes", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AppPicker(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember {
        val pm = ctx.packageManager
        val browsers = Apps.browserPackages(ctx)
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName && it.first !in Apps.BLOCKED && it.first !in browsers }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
    var sel by remember { mutableStateOf(Apps.effectiveExtras(ctx)) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Extra allowed apps") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(apps) { (pkg, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { sel = if (pkg in sel) sel - pkg else sel + pkg }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(pkg in sel, null)
                        Spacer(Modifier.width(12.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton({ Apps.setExtras(ctx, sel); onClose() }) { Text("Save") } },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}
