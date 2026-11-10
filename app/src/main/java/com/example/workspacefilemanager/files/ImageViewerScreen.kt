package com.example.workspacefilemanager.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.workspacefilemanager.util.FileActions
import java.io.File

/**
 * Full-screen image viewer.
 *
 * Pinch to zoom, double-tap to toggle zoom, and swipe left/right through every
 * other image in the same folder.
 *
 * The previous version used Modifier.transformable, which consumed *every*
 * drag — so the pager could never receive a horizontal swipe. Gestures are now
 * handled explicitly and only consumed when they are actually zoom/pan, which
 * leaves single-finger swipes at 1x zoom to the pager.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ImageViewerScreen(
    filePath: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val file = remember(filePath) { File(filePath) }

    // Siblings let the user swipe through the whole album without going back.
    val images = remember(filePath) {
        val siblings = file.parentFile?.listFiles { f ->
            f.isFile && FileActions.isImage(f.name)
        }?.sortedBy { it.name.lowercase() }?.map { it.absolutePath } ?: emptyList()

        if (siblings.contains(filePath) || siblings.isEmpty()) siblings
        else siblings + filePath
    }

    // Keep the opened image first if the folder is unreadable.
    val pageList = remember(images, filePath) {
        if (images.isEmpty()) listOf(filePath) else images
    }

    val startIndex = remember(pageList, filePath) {
        pageList.indexOf(filePath).coerceAtLeast(0)
    }

    val pagerState = rememberPagerState(initialPage = startIndex) { pageList.size }

    val currentPath = pageList.getOrNull(pagerState.currentPage) ?: filePath
    val currentName = remember(currentPath) { File(currentPath).name }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            currentName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (pageList.size > 1) {
                            Text(
                                "${pagerState.currentPage + 1} of ${pageList.size}  ·  pinch to zoom",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { FileActions.share(context, File(currentPath)) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { FileActions.openExternally(context, File(currentPath)) }) {
                        Icon(Icons.Default.OpenInNew, contentDescription = "Open with")
                    }
                    IconButton(onClick = {
                        val f = File(currentPath)
                        val result = FileActions.delete(context, f)
                        FileActions.toast(context, result.message ?: "Deleted ${f.name}")
                        if (result.success) onBack()
                    }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            // Only allow page changes while not zoomed in — handled per-page.
            userScrollEnabled = true
        ) { page ->
            val path = pageList.getOrNull(page) ?: return@HorizontalPager
            ZoomableImage(path = path)
        }
    }
}

private const val MAX_SCALE = 6f
private const val TAP_SLOP_PX = 24f

@Composable
private fun ZoomableImage(path: String) {
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var size by remember(path) { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(path) {
                var lastTapUp = 0L

                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)

                    var travelled = Offset.Zero
                    var transformed = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val pan = event.calculatePan()
                        travelled += pan

                        if (zoomChange != 1f) {
                            // Pinch: always ours.
                            scale = (scale * zoomChange).coerceIn(1f, MAX_SCALE)
                            offset = clampOffset(offset, size, scale)
                            transformed = true
                            event.changes.forEach { it.consume() }
                        } else if (scale > 1.01f) {
                            // Panning a zoomed image: ours.
                            offset = clampOffset(offset + pan, size, scale)
                            transformed = true
                            event.changes.forEach { it.consume() }
                        }
                        // At 1x with one finger we consume nothing, so the
                        // HorizontalPager receives the swipe.

                        if (event.changes.all { !it.pressed }) break
                    }

                    val isTap = travelled.getDistance() < TAP_SLOP_PX && !transformed
                    if (isTap) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapUp < 300L) {
                            // Double tap → toggle zoom
                            if (scale > 1.01f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                                offset = Offset.Zero
                            }
                            lastTapUp = 0L
                        } else {
                            lastTapUp = now
                        }
                    }
                }
            }
    ) {
        AsyncImage(
            model = File(path),
            contentDescription = File(path).name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size = it }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )

        if (scale > 1.01f) {
            // Quick way back to fit without hunting for a double tap.
            SmallFloatingActionButton(
                onClick = {
                    scale = 1f
                    offset = Offset.Zero
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
                containerColor = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White
            ) {
                Icon(Icons.Default.ZoomOutMap, contentDescription = "Fit to screen")
            }
        }
    }
}

/** Keep the image from being dragged off screen. */
private fun clampOffset(offset: Offset, size: IntSize, scale: Float): Offset {
    if (scale <= 1.01f || size.width == 0 || size.height == 0) return Offset.Zero
    val maxX = size.width * (scale - 1f) / 2f
    val maxY = size.height * (scale - 1f) / 2f
    return Offset(
        offset.x.coerceIn(-maxX, maxX),
        offset.y.coerceIn(-maxY, maxY)
    )
}
