package com.xmu.assistant

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh

/**
 *地图选点改为自绘瓦片，彻底不依赖高德 SDK。
 *
 * 背景：轻量版 SDK 内置的 JS API Key 已被高德判 INVALID_USER_KEY（CDP 抓到的
 * `<AMap JSAPI> KEY异常`），官方没有修复版，`MapsInitializer.setApiKey()` 写入的
 * Android 平台 Key 也进不了 JS 层。与其继续和黑盒较劲，不如直接用公开瓦片：
 * 零 Key、零 SDK、零鉴权往返，首次出图也只有一次 PNG 请求。
 *
 * 坐标系沿用项目原有的 GCJ-02（界面上显示 `· GCJ-02`）：高德瓦片本身就是按
 * GCJ-02 切片的，把 GCJ-02 经纬度直接代入 Web Mercator 即可，不需要额外偏移换算。
 */
private const val TILE = 256
private const val TILE_DEFAULT_LAT = 24.6096
private const val TILE_DEFAULT_LNG = 118.3130
private const val MIN_ZOOM = 3
private const val MAX_ZOOM = 20
/** 公开瓦片实际有数据的最高级别；再往上不再请求，改为把这一级瓦片放大绘制。 */
private const val TILE_MAX_ZOOM = 19
/**
 * style=8 中文注记层到 z=18 就没有数据了（z>=19 一律返回 192 字节全透明空图），
 * 比底图早一级。放大到 z=19/20 时不能按底图级别去请求注记，改为取 z=18 瓦片的
 * 对应象限放大绘制，否则地名会整片消失。
 */
private const val LABEL_TILE_MAX_ZOOM = 18
/** 失败瓦片的重试冷却；同时作为「延迟重绘」的间隔。 */
private const val RETRY_COOLDOWN_MS = 4000L
/** 注记层瓦片在缓存里的 key 前缀，与底图 key 区分。 */
private const val LABEL_PREFIX = "L/"
/**
 * 是否叠加 style=8 中文注记层。默认「无字版」：该层把灰字、
 * 棕路名、橙字餐饮、白字黑框楼栋名全烧在一张 PNG 里，样式互相打架且和高德 App
 * 不一致。底图(scl=2&style=7)本身完全不含文字，关掉后地图只剩道路/绿地/建筑轮廓。
 * 注记层加载与绘制逻辑全部保留，把这里改回 true 即可恢复带地名版本。
 */
private const val SHOW_LABEL_TILES = false

private fun worldSize(zoom: Int): Double {
    val tileZoom = zoom.coerceAtMost(TILE_MAX_ZOOM)
    val over = if (zoom > TILE_MAX_ZOOM) (1 shl (zoom - tileZoom)).toDouble() else 1.0
    return TILE.toDouble() * (1L shl tileZoom).toDouble() * over
}

private fun lngToX(lng: Double, zoom: Int): Double = (lng + 180.0) / 360.0 * worldSize(zoom)

private fun latToY(lat: Double, zoom: Int): Double {
    val clamped = lat.coerceIn(-85.05112878, 85.05112878)
    val s = sin(clamped * PI / 180.0)
    return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * worldSize(zoom)
}

private fun xToLng(x: Double, zoom: Int): Double = x / worldSize(zoom) * 360.0 - 180.0

private fun yToLat(y: Double, zoom: Int): Double {
    val n = PI * (1.0 - 2.0 * y / worldSize(zoom))
    return atan(sinh(n)) * 180.0 / PI
}

/** 高德的「无数据」瓦片是整张单色图（通常 179 字节纯白），采样四点即可判定。 */
private fun isBlankTile(b: Bitmap): Boolean {
    val p0 = b.getPixel(0, 0)
    return b.getPixel(b.width / 2, b.height / 2) == p0 &&
        b.getPixel(b.width - 1, b.height - 1) == p0 &&
        b.getPixel(b.width / 4, b.height * 3 / 4) == p0
}

/** 用 Canvas 画 256×256 栅格瓦片；拖动/缩放只改中心点，不重建 View。 */
internal class TileMapView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pool = Executors.newFixedThreadPool(6) { r -> Thread(r).apply { isDaemon = true } }
    // 按真实内存计。原来设 8MB 只有 32 张，而这台机一屏要 40+ 张，
    // LruCache 会反复淘汰「最早被 get 的」顶部瓦片，下一帧又重新下载 —— 就是「上方瓦片一直在刷」。
    // 多了注记层后一屏约 80 张位图（约 20MB），缓存提到 48MB 才不会抖动。
    private val cache = object : LruCache<String, Bitmap>(48 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val pending = Collections.synchronizedSet(mutableSetOf<String>())
    private val failedAt = Collections.synchronizedMap(mutableMapOf<String, Long>())
    /** 高德在无数据区域会返回纯色空白图，这类格子改由上一级瓦片放大填补。 */
    private val blank = Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var redrawQueued = false
    @Volatile private var retryScheduled = false
    @Volatile private var detached = false

    /** 一批瓦片同时到达时只重绘一次，避免每张瓦片都触发一帧全屏刷新。 */
    private fun requestRedraw() {
        if (redrawQueued) return
        redrawQueued = true
        post {
            redrawQueued = false
            invalidate()
        }
    }

    /**
     * 重试完全由 onDraw 驱动，而 onDraw 只在 invalidate 时才跑。
     * 以前瓦片失败后既不重绘也不重试，顶部最先发出的那一行请求一旦超时就永久空白 ——
     * 这正是「顶部第一行瓦片一直空缺」的根因。这里保证冷却到期后一定再来一帧。
     */
    private fun scheduleRetry() {
        if (retryScheduled || detached) return
        retryScheduled = true
        postDelayed({
            retryScheduled = false
            invalidate()
        }, RETRY_COOLDOWN_MS + 500L)
    }

    private var zoom = 17
    private var centerX = 0.0
    private var centerY = 0.0
    private var scaleAccum = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    /** 手势结束后回传中心点（GCJ-02 纬度、经度）。 */
    var onCenterChanged: ((Double, Double) -> Unit)? = null

    /** 瓦片下载失败时给界面一条可读原因，避免静默白屏。 */
    var onTileError: ((String) -> Unit)? = null

    fun setCenter(lat: Double, lng: Double, z: Int = 17) {
        zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        centerX = lngToX(lng, zoom)
        centerY = latToY(lat, zoom)
        invalidate()
    }

    /** 右下角 +/- 按钮用：以当前中心为锚点缩放一级，同时刷新回调坐标。 */
    fun zoomBy(delta: Int) {
        val next = (zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (next == zoom) return
        val lat = yToLat(centerY, zoom)
        val lng = xToLng(centerX, zoom)
        zoom = next
        centerX = lngToX(lng, zoom)
        centerY = latToY(lat, zoom)
        clamp()
        notifyCenter()
        invalidate()
    }

    private fun clamp() {
        centerY = centerY.coerceIn(0.0, worldSize(zoom))
        // centerX 允许环绕，onDraw 里对瓦片 x 取模
    }

    private fun notifyCenter() {
        onCenterChanged?.invoke(yToLat(centerY, zoom), xToLng(centerX, zoom))
    }

    /**
     * 双指缩放：
     * - 阈值从 1.35/0.74 调到 1.18/0.85，捏合一级更跟手；
     * - **不再清空缓存和请求队列**：cache 的 key 带 zoom，不同级别的瓦片可以共存，
     *清掉反而会让「正在飞的旧请求」和「新级别请求」打架，缩放瞬间闪白块；
     * - 以双指中心为锚点换算，保证缩放前后该点对应的经纬度不动。
     */
    private val scaleDetector =
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                scaleAccum = 1f
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                scaleAccum *= d.scaleFactor
                if (scaleAccum >= 1.18f || scaleAccum <= 0.85f) {
                    val next = (zoom + if (scaleAccum >= 1.18f) 1 else -1)
                        .coerceIn(MIN_ZOOM, MAX_ZOOM)
                    scaleAccum = 1f
                    if (next != zoom) {
                        val fx = d.focusX - width / 2f
                        val fy = d.focusY - height / 2f
                        val lat = yToLat(centerY + fy, zoom)
                        val lng = xToLng(centerX + fx, zoom)
                        zoom = next
                        centerX = lngToX(lng, zoom) - fx
                        centerY = latToY(lat, zoom) - fy
                        clamp()
                        notifyCenter()
                        invalidate()
                    }
                }
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                scaleAccum = 1f
                notifyCenter()
            }
        })

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 必须自己裁一刀：这个 ROM（荣耀 MagicOS）上 Compose 的 AndroidView 没有把原生 View 的
        // 绘制裁到自身边界，canvas 的 clip 是整窗。叠加瓦片时为了不留缝会多画外圈
        // （y0-1 / y1+1），那一圈用负坐标画到了 View 上方，连同 drawColor 的底色一起
        // 盖到标题行上 —— 屏幕上就是「顶部一条浅灰空带 + 地图选点标题和取消按钮消失」。
        // 和瓦片请求、级别、缓存都无关，所以此前怎么改重试都无效果。
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawColor(Color.rgb(0xED, 0xEF, 0xF1))
        val z = zoom
        // 超级别缩放：z=20 不再向服务器要 z=20 的空白瓦片，而是把 z=19 的瓦片放大 2 倍画出来。
        val tileZoom = z.coerceAtMost(TILE_MAX_ZOOM)
        val count = 1 shl tileZoom
        val scale = if (z > TILE_MAX_ZOOM) (1 shl (z - tileZoom)).toDouble() else 1.0
        val span = TILE * scale
        val left = centerX - width / 2.0
        val top = centerY - height / 2.0
        val x0 = floor(left / span).toInt()
        val y0 = floor(top / span).toInt()
        val x1 = floor((left + width) / span).toInt()
        val y1 = floor((top + height) / span).toInt()
        val now = System.currentTimeMillis()
        // 先画完所有底图再统一叠注记，保证底图请求先入队、不被注记拖慢。
        val visible = ArrayList<Triple<Int, Int, RectF>>()
        // 外扩一圈：边界取整在不同屏幕尺寸上会差半格，多画一圈由 Canvas 自行裁剪，
        // 代价极小，但能避免最上面一行/最左边一列被漏掉。
        for (ty in (y0 - 1)..(y1 + 1)) {
            if (ty < 0 || ty >= count) continue
            for (tx in (x0 - 1)..(x1 + 1)) {
                val wrappedX = ((tx % count) + count) % count
                val key = "$tileZoom/$wrappedX/$ty"
                val bmp = cache.get(key)
                val isBlank = synchronized(blank) { key in blank }
                val dx = (tx * span - left).toFloat()
                val dy = (ty * span - top).toFloat()
                val dst = RectF(dx, dy, dx + span.toFloat(), dy + span.toFloat())
                // 完全在屏幕外的外扩格子直接跳过，别让注记层跟着多发一倍请求。
                if (dst.right <= 0 || dst.left >= width || dst.bottom <= 0 || dst.top >= height) continue
                visible.add(Triple(wrappedX, ty, dst))
                if (bmp != null && !isBlank) {
                    canvas.drawBitmap(bmp, null, dst, paint)
                } else {
                    // 兜底：逐级向上找有数据的祖先瓦片，裁出对应子块放大绘制。
                    // 低级别数据总是完整的，任意一级失败/空白都不会留出整行浅灰空带。
                    var filled = false
                    var ancestorZoom = tileZoom
                    var ancestorX = wrappedX
                    var ancestorY = ty
                    for (step in 1..3) {
                        ancestorZoom -= 1
                        if (ancestorZoom < MIN_ZOOM) break
                        ancestorX /= 2
                        ancestorY /= 2
                        val ancestorKey = "$ancestorZoom/$ancestorX/$ancestorY"
                        val ancestor = cache.get(ancestorKey)
                        val ancestorBlank = synchronized(blank) { ancestorKey in blank }
                        if (ancestor != null && !ancestorBlank) {
                            val factor = 1 shl step
                            val sub = TILE / factor
                            val offX = (wrappedX % factor) * sub
                            val offY = (ty % factor) * sub
                            canvas.drawBitmap(
                                ancestor,
                                Rect(offX, offY, offX + sub, offY + sub),
                                dst,
                                paint,
                            )
                            filled = true
                            break
                        }
                        if (now - (failedAt[ancestorKey] ?: 0L) >= RETRY_COOLDOWN_MS) {
                            loadTile(ancestorZoom, ancestorX, ancestorY, ancestorKey)
                        }
                    }
                    // 本级别继续尝试：祖先图只是临时兜底，拿到真图后会自动覆盖。
                    // 被判空白的格子不重发（服务端对同一格永远返回同一张空图）。
                    if (!isBlank && now - (failedAt[key] ?: 0L) >= RETRY_COOLDOWN_MS) {
                        loadTile(tileZoom, wrappedX, ty, key)
                    }
                    // 只要这一格还没画出任何东西就安排延迟重绘。这里不能排除 isBlank：
                    // 空白格本身不重发请求，若它的上级瓦片也没到位，就再没有任何一帧
                    // 会来补它 —— 这正是顶部那一行「一直」空着的最后一环。
                    if (!filled) scheduleRetry()
                }
            }
        }
        // 第二遍：叠加地名注记。无字版下整段跳过，底图出图后直接结束。
        if (!SHOW_LABEL_TILES) return
        // scl=2 高分底图本身不带任何文字，地名全靠 style=8 层。
        // 注记层只到 z=18 有数据，级别更高时取 z=18 瓦片的对应象限放大绘制。
        val labelZoom = tileZoom.coerceAtMost(LABEL_TILE_MAX_ZOOM)
        val labelShift = tileZoom - labelZoom
        val labelFactor = 1 shl labelShift
        val subSize = TILE / labelFactor
        for ((wx, ty, dst) in visible) {
            val lx = wx shr labelShift
            val ly = ty shr labelShift
            val labelKey = "$LABEL_PREFIX$labelZoom/$lx/$ly"
            val label = cache.get(labelKey)
            if (label != null) {
                if (labelFactor == 1) {
                    canvas.drawBitmap(label, null, dst, paint)
                } else {
                    val offX = (wx % labelFactor) * subSize
                    val offY = (ty % labelFactor) * subSize
                    canvas.drawBitmap(
                        label,
                        Rect(offX, offY, offX + subSize, offY + subSize),
                        dst,
                        paint,
                    )
                }
            } else if (now - (failedAt[labelKey] ?: 0L) >= RETRY_COOLDOWN_MS) {
                loadLabel(labelZoom, lx, ly, labelKey)
                scheduleRetry()
            }
        }
    }

    /** style=8 中文注记层：透明 PNG，失败只影响文字，绝不阻塞底图出图。 */
    private fun loadLabel(z: Int, x: Int, y: Int, key: String) {
        synchronized(pending) { if (!pending.add(key)) return }
        val server = (x + y) % 4 + 1
        val url = "https://wprd0$server.is.autonavi.com/appmaptile" +
            "?x=$x&y=$y&z=$z&lang=zh_cn&size=1&scl=1&style=8"
        pool.execute {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { src ->
                    if (src.width == TILE && src.height == TILE) src
                    else {
                        val scaled = Bitmap.createScaledBitmap(src, TILE, TILE, true)
                        src.recycle()
                        scaled
                    }
                }
                if (bmp != null) {
                    cache.put(key, bmp)
                    synchronized(failedAt) { failedAt.remove(key) }
                    requestRedraw()
                } else {
                    synchronized(failedAt) { failedAt[key] = System.currentTimeMillis() }
                }
                synchronized(pending) { pending.remove(key) }
            } catch (_: Exception) {
                synchronized(pending) { pending.remove(key) }
                synchronized(failedAt) { failedAt[key] = System.currentTimeMillis() }
            }
        }
    }

    private fun loadTile(z: Int, x: Int, y: Int, key: String) {
        synchronized(pending) { if (!pending.add(key)) return }
        // scl=2 会返回 512×512 高分瓦片，且 z=19/20 仍有真实内容
        // （scl=1 到 z=19 就基本是空白图了）。z=21 起服务器一律返回空白，所以 MAX_ZOOM=20。
        val server = (x + y) % 4 + 1
        val url = "https://wprd0$server.is.autonavi.com/appmaptile" +
            "?x=$x&y=$y&z=$z&lang=zh_cn&size=1&scl=2&style=7"
        pool.execute {
            /** 解码失败和网络异常都必须释放 pending 并登记冷却，否则这一格永久卡死。 */
            fun fail(reason: String) {
                synchronized(pending) { pending.remove(key) }
                synchronized(failedAt) { failedAt[key] = System.currentTimeMillis() }
                post { onTileError?.invoke(reason) }
                requestRedraw()
            }
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 12000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                // 高分图先降采样回 256 再入缓存：内存占用与之前一致，
                // 24MB 缓存仍然装得下整屏瓦片，不会重演「上方一直刷」。
                val bmp = raw?.let { src ->
                    if (src.width == TILE && src.height == TILE) src
                    else {
                        val scaled = Bitmap.createScaledBitmap(src, TILE, TILE, true)
                        src.recycle()
                        scaled
                    }
                }
                if (bmp == null) {
                    fail("瓦片为空 $key")
                } else {
                    // 高德「无数据」瓦片约 179 字节、整张单色。必须同时满足「极小」
                    // 和「单色」，否则大片纯色（水面/绿地）的正常瓦片会被误杀成空白。
                    if (bytes.size <= 400 && isBlankTile(bmp)) {
                        synchronized(blank) { blank.add(key) }
                    } else {
                        cache.put(key, bmp)
                    }
                    synchronized(pending) { pending.remove(key) }
                    synchronized(failedAt) { failedAt.remove(key) }
                    requestRedraw()
                }
            } catch (e: Exception) {
                fail("${e.javaClass.simpleName}: ${e.message.orEmpty().take(60)}")
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                scaleAccum = 1f
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指落下时同步拖动基准，否则回到单指会算出一个巨大位移、地图猛跳
                val idx = event.actionIndex
                activePointerId = event.getPointerId(idx)
                lastX = event.getX(idx)
                lastY = event.getY(idx)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val found = event.findPointerIndex(activePointerId)
                    val idx = if (found >= 0) found else 0
                    val x = event.getX(idx)
                    val y = event.getY(idx)
                    centerX -= (x - lastX).toDouble()
                    centerY -= (y - lastY).toDouble()
                    lastX = x
                    lastY = y
                    clamp()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 抬起的正好是基准指针，就把基准切到还留在屏幕上的那根
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    val idx = if (event.actionIndex == 0) 1 else 0
                    if (idx < event.pointerCount) {
                        activePointerId = event.getPointerId(idx)
                        lastX = event.getX(idx)
                        lastY = event.getY(idx)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                notifyCenter()
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        detached = true
        pool.shutdownNow()
    }
}

private fun Context.findHostActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * 全屏选点对话框。窗口几何与底部 48dp 留白沿用已真机验证过的写法
 * （这台荣耀的 getRootWindowInsets 会返回 bottom=0，不能只靠系统 inset）。
 */
@Composable
internal fun TileMapPickerDialog(
    initialLatitude: Double?,
    initialLongitude: Double?,
    onPicked: (Double, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val startLat = remember(initialLatitude) {
        initialLatitude?.takeIf { it in -90.0..90.0 } ?: TILE_DEFAULT_LAT
    }
    val startLng = remember(initialLongitude) {
        initialLongitude?.takeIf { it in -180.0..180.0 } ?: TILE_DEFAULT_LNG
    }
    var lat by remember { mutableStateOf(startLat) }
    var lng by remember { mutableStateOf(startLng) }
    var tileError by remember { mutableStateOf("") }
    var mapViewRef by remember { mutableStateOf<TileMapView?>(null) }
    LaunchedEffect(tileError) {
        if (tileError.isNotBlank()) {
            delay(4000)
            tileError = ""
        }
    }

    val context = LocalContext.current
    val density = LocalDensity.current
    val insets = remember(context) {
        val decor = context.findHostActivity()?.window?.decorView
        decor?.let { ViewCompat.getRootWindowInsets(it) }
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
    }
    val leftInset = with(density) { (insets?.left ?: 0).toDp() }
    val rightInset = with(density) { (insets?.right ?: 0).toDp() }

    // 不再让窗口越出屏幕。之前用 FLAG_LAYOUT_NO_LIMITS + setDecorFitsSystemWindows(false)
    // + 手动 MATCH_PARENT，窗口 frame 变成 [0,126][1199,2791]（底部溢出屏幕 127px），
    // 系统给窗口的 surfaceInsets 又是 104，导致内容整体上移 —— 标题栏被画到地图底图色之上，
    // 屏幕上看就是顶部一整条浅灰空带、标题和取消按钮一起消失。
    // 现在交回系统计算窗口边界（默认就不含状态栏），底部留白仍由 48dp 兜底。
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(dialogWindow) {
            dialogWindow?.let { w ->
                // 关键：清掉窗口背景与变暗。Dialog 默认是"带阴影的浮动窗口"，系统会给它
                // surfaceInsets=Rect(104,104,-104,-104)，并把窗口高度按满屏算（2665 > 可见 2538）。
                // 里面的 AndroidView(TileMapView) 是按 surface 坐标绘制的，于是整块地图被上移约 136px，
                // 压到标题行上 —— 屏幕表现就是顶部一条浅灰带、标题和取消按钮消失，
                // 而纯 Compose 的按钮因为在地图下方不受影响。去掉背景后 surfaceInsets 归零。
                w.setBackgroundDrawableResource(android.R.color.transparent)
                w.setDimAmount(0f)
                w.setGravity(Gravity.TOP or Gravity.START)
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxSize().padding(
                    start = leftInset,
                    end = rightInset,
                    bottom = 48.dp,
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PrivacyText(
                            "地图选点",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        PrivacyText(
                            "拖动地图，让准星对准签到地点",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onDismiss) { PrivacyText("取消") }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            TileMapView(ctx).apply {
                                setCenter(startLat, startLng, 17)
                                onCenterChanged = { a, b -> lat = a; lng = b }
                                onTileError = { msg -> tileError = msg }
                            }.also { mapViewRef = it }
                        },
                        update = { /* 状态都在 View 内部，重组时不重置地图 */ },
                    )
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(22.dp)
                            .shadow(3.dp, CircleShape)
                            .background(ComposeColor.White, CircleShape),
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                    }
                    // 双指缩放的兜底：不管手势在什么 ROM 上表现如何，这两个按钮始终能缩放。
                    Column(
                        Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(
                            onClick = { mapViewRef?.zoomBy(1) },
                            modifier = Modifier.size(44.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) { PrivacyText("+") }
                        OutlinedButton(
                            onClick = { mapViewRef?.zoomBy(-1) },
                            modifier = Modifier.size(44.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) { PrivacyText("\u2212") }
                    }
                }
                Column(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrivacyText(
                        String.format(Locale.US, "%.6f, %.6f · %s", lat, lng, RADAR_COORDINATE_SYSTEM),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    PrivacyText(
                        "拖动地图对准地点，双指缩放，再点下面的按钮",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (tileError.isNotBlank()) {
                        PrivacyText(
                            "地图瓦片加载失败：$tileError",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = { onPicked(lat, lng) },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    ) { PrivacyText("用这个点") }
                }
            }
        }
    }
}
