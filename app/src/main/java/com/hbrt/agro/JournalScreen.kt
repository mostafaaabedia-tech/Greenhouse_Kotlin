package com.hbrt.agro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Reads a picked photo, shrinks it so the long side is at most [side] px and returns a JPEG data URL. */
private fun shrink(c: Context, uri: Uri, side: Int, q: Int): String? = try {
    val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { d, info, _ ->
            val s = max(info.size.width, info.size.height)
            if (s > side) d.setTargetSize(info.size.width * side / s, info.size.height * side / s)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= side) sample *= 2
        val b = c.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
        val s = max(b.width, b.height)
        if (s > side) Bitmap.createScaledBitmap(b, b.width * side / s, b.height * side / s, true) else b
    }
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
    "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
} catch (e: Exception) { null }

private fun decodeDataUrl(s: String): ImageBitmap? = try {
    val b = Base64.decode(s.substringAfter(","), Base64.DEFAULT)
    BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
} catch (e: Exception) { null }

@Composable
fun JournalScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ro by GreenhouseClient.readOnly.collectAsStateWithLifecycle()
    val r by GreenhouseClient.latest.collectAsStateWithLifecycle()
    var list by remember { mutableStateOf<List<GreenhouseClient.Entry>?>(null) }
    var noCloud by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf("note") }
    var text by remember { mutableStateOf("") }
    var grams by remember { mutableStateOf("") }
    var thumb by remember { mutableStateOf<String?>(null) }
    var full by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var askDelete by remember { mutableStateOf<GreenhouseClient.Entry?>(null) }
    var viewing by remember { mutableStateOf<GreenhouseClient.Entry?>(null) }
    val notSent = stringResource(R.string.not_sent)

    LaunchedEffect(reload, r?.id) {
        val j = GreenhouseClient.journal()
        noCloud = j == null && list == null
        if (j != null) list = j
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val (t, f) = withContext(Dispatchers.Default) { shrink(ctx, uri, 320, 60) to shrink(ctx, uri, 1280, 72) }
            thumb = t; full = f
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            val total = list?.filter { it.k == "harvest" }?.sumOf { it.g.toDouble() }?.toFloat() ?: 0f
            PageTitle(stringResource(R.string.j_title), if (total > 0) stringResource(R.string.j_total, total.f0()) else null)
        }
        if (noCloud) item { Notice(stringResource(R.string.j_needs_cloud), Muted) }

        if (!ro && !noCloud) item {
            Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("note" to R.string.j_note, "photo" to R.string.j_photo, "harvest" to R.string.j_harvest).forEach { (k, n) ->
                        val sel = kind == k
                        val c = when (k) { "photo" -> Violet; "harvest" -> Gold; else -> Neon }
                        Row(
                            Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (sel) c.copy(alpha = .18f) else CardHi)
                                .border(1.dp, if (sel) c else Border, RoundedCornerShape(16.dp)).bounceClick { kind = k }.padding(vertical = 11.dp),
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                        ) {
                            KindIcon(k, if (sel) c else Muted, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(n), color = if (sel) c else TextC, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                AnimatedVisibility(kind == "photo") {
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(18.dp)).background(CardHi)
                            .bounceClick { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        contentAlignment = Alignment.Center
                    ) {
                        val img = remember(thumb) { thumb?.let(::decodeDataUrl) }
                        if (img != null) Image(img, null, Modifier.fillMaxWidth().heightIn(max = 220.dp), contentScale = ContentScale.Crop)
                        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            KindIcon("photo", Violet, Modifier.size(30.dp))
                            Text(stringResource(R.string.j_pick), color = Muted, fontSize = 13.sp)
                        }
                    }
                }
                AnimatedVisibility(kind == "harvest") {
                    OutlinedTextField(grams, { grams = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text(stringResource(R.string.j_grams)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = field(), modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(text, { text = it }, placeholder = { Text(stringResource(R.string.j_text_ph)) }, colors = field(), modifier = Modifier.fillMaxWidth(), minLines = 2)
                val g = grams.toFloatOrNull() ?: 0f
                val can = !saving && when (kind) { "photo" -> thumb != null; "harvest" -> g > 0; else -> text.isNotBlank() }
                PrimaryButton(stringResource(if (saving) R.string.j_saving else R.string.j_save), can) {
                    saving = true
                    scope.launch {
                        val ok = GreenhouseClient.addEntry(kind, text.trim(), g, if (kind == "photo") thumb else null, if (kind == "photo") full else null)
                        saving = false
                        if (ok) { text = ""; grams = ""; thumb = null; full = null; reload++ } else toast(ctx, notSent)
                    }
                }
            }
        }

        val l = list
        if (l == null && !noCloud) item { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Neon) } }
        if (l != null && l.isEmpty()) item { Text(stringResource(R.string.j_empty), color = Muted, fontSize = 14.sp) }
        if (l != null) items(l, key = { it.id }) { e -> JournalRow(e, !ro, onOpen = { viewing = e }, onDelete = { askDelete = e }) }
    }

    askDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { askDelete = null }, containerColor = CardC,
            title = { Text(stringResource(R.string.j_delete_q)) },
            confirmButton = { TextButton(onClick = { askDelete = null; list = list?.filter { it.id != e.id }; scope.launch { GreenhouseClient.deleteEntry(e.id) } }) { Text(stringResource(R.string.delete), color = Danger) } },
            dismissButton = { TextButton(onClick = { askDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    viewing?.let { e ->
        var big by remember(e.id) { mutableStateOf<ImageBitmap?>(decodeDataUrl(e.thumb)) }
        LaunchedEffect(e.id) { GreenhouseClient.photo(e.id)?.let { s -> withContext(Dispatchers.Default) { decodeDataUrl(s) } }?.let { big = it } }
        Dialog(onDismissRequest = { viewing = null }) {
            Column(Modifier.clip(RoundedCornerShape(24.dp)).background(CardC).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                big?.let { Image(it, null, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.FillWidth) }
                if (e.x.isNotBlank()) Text(e.x, color = TextC, fontSize = 14.sp)
                SecondaryButton(stringResource(R.string.close)) { viewing = null }
            }
        }
    }
}

@Composable
private fun JournalRow(e: GreenhouseClient.Entry, canDelete: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val c = when (e.k) { "photo" -> Violet; "harvest" -> Gold; else -> Neon }
    Row(Modifier.fillMaxWidth().card().padding(14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            KindIcon(e.k, c, Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(dateStr(e.t) + " · " + timeStr(e.t), color = Muted, fontSize = 12.sp)
            if (e.k == "harvest") Text(stringResource(R.string.j_harvested, e.g.f0()), color = Gold, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (e.thumb.isNotBlank()) {
                val img = remember(e.id) { decodeDataUrl(e.thumb) }
                if (img != null) Image(img, null, Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(14.dp)).bounceClick(onClick = onOpen), contentScale = ContentScale.Crop)
            }
            if (e.x.isNotBlank()) Text(e.x, color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
        }
        if (canDelete) Icon(Icons.Rounded.Delete, stringResource(R.string.delete), tint = Muted, modifier = Modifier.padding(start = 6.dp).size(22.dp).clip(CircleShape).bounceClick(onClick = onDelete))
    }
}

/** Small line icons for the journal kinds: note, photo, harvest. */
@Composable
private fun KindIcon(kind: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height; val sw = w * .11f
        val st = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        when (kind) {
            "photo" -> {
                drawRoundRect(color, androidx.compose.ui.geometry.Offset(w * .08f, h * .28f), androidx.compose.ui.geometry.Size(w * .84f, h * .6f), androidx.compose.ui.geometry.CornerRadius(w * .14f), style = st)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * .36f, h * .16f), androidx.compose.ui.geometry.Offset(w * .64f, h * .16f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
                drawCircle(color, w * .15f, androidx.compose.ui.geometry.Offset(w * .5f, h * .58f), style = st)
            }
            "harvest" -> {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * .2f, h * .82f); cubicTo(w * .1f, h * .3f, w * .5f, h * .12f, w * .86f, h * .14f)
                    cubicTo(w * .9f, h * .55f, w * .65f, h * .86f, w * .2f, h * .82f); close()
                }
                drawPath(p, color, style = st)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * .2f, h * .82f), androidx.compose.ui.geometry.Offset(w * .62f, h * .4f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            else -> {
                drawRoundRect(color, androidx.compose.ui.geometry.Offset(w * .16f, h * .08f), androidx.compose.ui.geometry.Size(w * .68f, h * .84f), androidx.compose.ui.geometry.CornerRadius(w * .12f), style = st)
                for (i in 0..2) drawLine(color, androidx.compose.ui.geometry.Offset(w * .32f, h * (.32f + i * .18f)), androidx.compose.ui.geometry.Offset(w * (if (i == 2) .52f else .68f), h * (.32f + i * .18f)), sw, androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}
