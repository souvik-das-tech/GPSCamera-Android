// The app's own photos and videos: a grid, and a swipeable full-screen viewer with share and
// delete. (Each one is also saved to the phone's gallery, in the "Your GPS Camera" album.)
package com.bbcl.gpscamera

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** Decodes [file] off the main thread, about [size] pixels on its short side. */
@Composable
fun rememberThumb(file: File, size: Int): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return image
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var media by remember { mutableStateOf(Media.list(context)) }
    var viewing by remember { mutableStateOf<Int?>(null) }

    BackHandler(onBack = onBack)
    viewing?.let { start ->
        Viewer(media, start, onClose = {
            media = Media.list(context)
            viewing = null
        })
        return
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("Photos & videos") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
            )
        },
    ) { pad ->
        if (media.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing yet.\nPhotos and videos you take appear here and in your phone's gallery (album \"${Media.ALBUM}\").",
                    textAlign = TextAlign.Center,
                    color = Color.White,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.padding(pad),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(media, key = { _, f -> f.path }) { i, f ->
                    Box(
                        Modifier.aspectRatio(1f).background(Color.White.copy(alpha = 0.1f)).clickable { viewing = i },
                        contentAlignment = Alignment.Center,
                    ) {
                        val thumb = rememberThumb(Media.coverImage(context, f), 300)
                        if (thumb != null) {
                            Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else if (Media.isVideo(f)) {
                            Icon(Icons.Filled.Videocam, contentDescription = null, tint = Color.White.copy(alpha = 0.55f))
                        }
                        if (Media.isVideo(f)) {
                            Icon(Icons.Filled.PlayCircleFilled, contentDescription = "Video", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(36.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Viewer(initialMedia: List<File>, start: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    var media by remember { mutableStateOf(initialMedia) }
    val pager = rememberPagerState(initialPage = start) { media.size }
    var confirmDelete by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)
    val current = media.getOrNull(pager.currentPage)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("${pager.currentPage + 1} / ${media.size}") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = {
                        val f = current ?: return@IconButton
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                        val send = Intent(Intent.ACTION_SEND)
                            .setType(if (Media.isVideo(f)) "video/mp4" else "image/jpeg")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, null))
                    }) { Icon(Icons.Filled.Share, contentDescription = "Share") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
            )
        },
    ) { pad ->
        HorizontalPager(pager, Modifier.fillMaxSize().padding(pad), key = { media[it].path }) { i ->
            val f = media[i]
            if (Media.isVideo(f)) VideoPage(f, isCurrent = pager.currentPage == i) else PhotoPage(f)
        }
    }

    if (confirmDelete && current != null) {
        val video = Media.isVideo(current)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this ${if (video) "video" else "photo"}?") },
            text = { Text("It is removed from this app. The copy in your phone's gallery is kept.") },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            confirmButton = {
                Button(onClick = {
                    current.delete()
                    if (video) Media.thumbFor(context, current).delete()
                    confirmDelete = false
                    media = media - current
                    if (media.isEmpty()) onClose()
                }) { Text("Delete") }
            },
        )
    }
}

@Composable
private fun PhotoPage(file: File) {
    val image = rememberThumb(file, 2000)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                offset = if (scale == 1f) Offset.Zero else offset + pan
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        if (image == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            )
        }
    }
}

/** Plays a video with the system controls; only the page on screen plays. */
@Composable
private fun VideoPage(file: File, isCurrent: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoPath(file.path)
                    val controls = MediaController(ctx)
                    controls.setAnchorView(this)
                    setMediaController(controls)
                    setOnPreparedListener { if (isCurrent) start() }
                }
            },
            update = { if (!isCurrent && it.isPlaying) it.pause() },
            onRelease = { it.stopPlayback() },
            // VideoView keeps the video's aspect ratio within these bounds.
            modifier = Modifier.fillMaxSize(),
        )
    }
}
