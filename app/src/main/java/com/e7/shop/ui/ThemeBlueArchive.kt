package com.e7.shop.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.e7.shop.ChangelogDialog
import com.e7.shop.R
import com.e7.shop.ShizukuCard
import com.e7.shop.ShopAccessibilityService
import com.e7.shop.data.AppConfig
import com.e7.shop.data.RecordStore
import com.e7.shop.decodeFileSafe
import com.e7.shop.loadBgBitmap
import com.e7.shop.loadLogoBitmap
import com.e7.shop.net.WebDavSync
import kotlinx.coroutines.launch


private val BaCyan = Color(0xFF1E88E5)
private val BaCyanDeep = Color(0xFF1565C0)
private val BaPink = Color(0xFFFF7FB6)
private val BaMint = Color(0xFF00B0A3)
private val BaInk = Color(0xFF12263D)
private val BaSky = Color(0xFFEAF3FC)

private val BlueArchiveScheme = lightColorScheme(
    primary = BaCyan,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E9FB),
    onPrimaryContainer = Color(0xFF0B3A66),
    secondary = BaMint,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9F0EC),
    onSecondaryContainer = Color(0xFF003A36),
    tertiary = BaPink,
    background = BaSky,
    onBackground = BaInk,
    surface = Color(0xFFFDFEFF),
    onSurface = BaInk,
    surfaceVariant = Color(0xFFE3EDF8),
    onSurfaceVariant = Color(0xFF4A5F75),
    outline = Color(0xFF9FB6CE),
    error = Color(0xFFD93025),
    onError = Color.White
)

private val BaShape = RoundedCornerShape(22.dp)

object BlueArchiveTheme : E7Theme {
    override val id = "ba"
    override val labelRes = R.string.appearance_ba
    @Composable override fun ColorSchemeProvider(content: @Composable () -> Unit) =
        MaterialTheme(colorScheme = BlueArchiveScheme, content = content)
    @Composable override fun HomeScreen(cfg: AppConfig, bot: ShopAccessibilityService.BotState, connected: Boolean) =
        BaHome(cfg, bot, connected)
    @Composable override fun RecordsScreen(cfg: AppConfig) = BaRecords(cfg)
    @Composable override fun ProfileScreen(cfg: AppConfig) = BaProfile(cfg)
    @Composable override fun SettingsScreen(cfg: AppConfig, onChangeAppearance: (String) -> Unit) =
        BaSettings(cfg, onChangeAppearance)
}

/* ---- BA 装饰组件 ---- */

/** BA 标志性网点半调：低透明度圆点网格。 */
@Composable
private fun HalftoneDots(
    modifier: Modifier = Modifier,
    color: Color = BaCyan,
    alpha: Float = 0.08f
) {
    Canvas(modifier = modifier) {
        val step = 22.dp.toPx()
        val r = 4.dp.toPx()
        var row = 0
        var y = step / 2
        while (y < size.height) {
            val offset = if (row % 2 == 0) 0f else step / 2
            var x = offset + step / 2
            while (x < size.width) {
                drawCircle(color = color.copy(alpha = alpha), radius = r, center = androidx.compose.ui.geometry.Offset(x, y))
                x += step
            }
            y += step
            row++
        }
    }
}

/** 横幅斜向条纹装饰。 */
@Composable
private fun BannerStripes(modifier: Modifier = Modifier, color: Color = Color.White, alpha: Float = 0.10f) {
    Canvas(modifier = modifier) {
        val stripe = 16.dp.toPx()
        val gap = 34.dp.toPx()
        var x = -size.height
        while (x < size.width + size.height) {
            drawLine(
                color = color.copy(alpha = alpha),
                start = androidx.compose.ui.geometry.Offset(x, size.height),
                end = androidx.compose.ui.geometry.Offset(x + size.height, 0f),
                strokeWidth = stripe
            )
            x += gap + stripe
        }
    }
}

/** 相框式卡片：白底 + 青边 + 双角粉色胶带装饰。 */
@Composable
private fun BaFrameCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(BaShape)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // 双角胶带
        Box(
            modifier = Modifier
                .padding(start = 12.dp, top = 8.dp)
                .size(width = 34.dp, height = 14.dp)
                .rotate(-12f)
                .background(BaPink.copy(alpha = 0.45f), RoundedCornerShape(3.dp))
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 12.dp, top = 8.dp)
                .size(width = 34.dp, height = 14.dp)
                .rotate(12f)
                .background(BaCyan.copy(alpha = 0.30f), RoundedCornerShape(3.dp))
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) { content() }
    }
}

/** 细描边圆角容器（选中态）。 */
@Composable
private fun BaCardShell(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(BaShape),
        shape = BaShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) BaCyan else BaCyan.copy(alpha = 0.35f)
        )
    ) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

/**
 * BA 角色素材加载：从 assets 读取用户自备的 Blue Archive 角色图
 * （如日奈/白子的 Q 版、头像、半身像或剪影，建议 1:1 透明底 PNG）。
 */
private fun loadBaAsset(context: Context, name: String): Bitmap? =
    try {
        context.assets.open(name).use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }

/**
 * BA 原生视觉元素 · 光环徽章（默认装饰）：
 * 青→粉 sweep 渐变光环 + 奇迹柔光 + 四道光芒 + 中心悬浮宝石 + 星屑。
 * 不描绘具体角色——它只负责传达 Blue Archive 本身的气质。
 */
@Composable
private fun BaHalo(modifier: Modifier = Modifier, size: Dp = 62.dp) {
    val cyan = BaCyan
    val pink = BaPink
    val gold = Color(0xFFF6DE8A)
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val r = w * 0.29f
        // 奇迹柔光（温柔、轻盈的白色光晕）
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.0f)),
                center = androidx.compose.ui.geometry.Offset(cx, cy),
                radius = r * 2.15f
            ),
            radius = r * 2.15f,
            center = androidx.compose.ui.geometry.Offset(cx, cy)
        )
        // 光环：青 → 粉 sweep 渐变圆环（BA 最具辨识度的原生意象）
        drawCircle(
            brush = Brush.sweepGradient(
                colors = listOf(cyan, pink, Color(0xFF8FD0FF), cyan),
                center = androidx.compose.ui.geometry.Offset(cx, cy)
            ),
            radius = r,
            center = androidx.compose.ui.geometry.Offset(cx, cy),
            style = Stroke(width = w * 0.075f, cap = StrokeCap.Round)
        )
        // 内圈细白线
        drawCircle(
            color = Color.White.copy(alpha = 0.75f),
            radius = r * 0.70f,
            center = androidx.compose.ui.geometry.Offset(cx, cy),
            style = Stroke(width = w * 0.020f)
        )
        // 四道短光芒（青春与"奇迹"的光感）
        val rayStart = r * 1.22f
        val rayEnd = r * 1.82f
        for (i in 0 until 4) {
            val ang = (i * 90f + 45f) * (Math.PI / 180f).toFloat()
            val dx = kotlin.math.cos(ang)
            val dy = kotlin.math.sin(ang)
            drawLine(
                color = Color.White.copy(alpha = 0.9f),
                start = androidx.compose.ui.geometry.Offset(cx + dx * rayStart, cy + dy * rayStart),
                end = androidx.compose.ui.geometry.Offset(cx + dx * rayEnd, cy + dy * rayEnd),
                strokeWidth = w * 0.030f,
                cap = StrokeCap.Round
            )
        }
        // 中心悬浮宝石（小菱形，金色点缀）
        val d = r * 0.30f
        drawPath(
            Path().apply {
                moveTo(cx, cy - d * 1.4f)
                lineTo(cx + d, cy)
                lineTo(cx, cy + d * 1.4f)
                lineTo(cx - d, cy)
                close()
            },
            color = gold
        )
        drawPath(
            Path().apply {
                moveTo(cx, cy - d * 0.55f)
                lineTo(cx + d * 0.4f, cy)
                lineTo(cx, cy + d * 0.55f)
                lineTo(cx - d * 0.4f, cy)
                close()
            },
            color = Color.White.copy(alpha = 0.85f)
        )
        // 星屑
        drawCircle(color = Color.White.copy(alpha = 0.8f), radius = w * 0.022f,
            center = androidx.compose.ui.geometry.Offset(cx - r * 0.95f, cy - r * 0.55f))
        drawCircle(color = pink.copy(alpha = 0.9f), radius = w * 0.018f,
            center = androidx.compose.ui.geometry.Offset(cx + r * 0.95f, cy + r * 0.45f))
        drawCircle(color = Color.White.copy(alpha = 0.6f), radius = w * 0.016f,
            center = androidx.compose.ui.geometry.Offset(cx - r * 0.75f, cy + r * 0.85f))
    }
}

/**
 * BA 角色素材位：
 *  - assets/ba_char_home.png    → 首页横幅右侧装饰
 *  - assets/ba_char_profile.png → 「我的」页顶部装饰
 *  - assets/ba_chibi.png        → 旧素材位（两个位置均兼容回退）
 * 放入日奈、白子等角色 PNG 即自动替换对应位置；未放置时渲染光环徽章。
 */
@Composable
private fun BaCharSlot(modifier: Modifier = Modifier, size: Dp = 62.dp, slot: String) {
    val context = LocalContext.current
    val bmp = remember {
        loadBaAsset(context, slot) ?: loadBaAsset(context, "ba_chibi.png")
    }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.size(size).clip(CircleShape)
        )
    } else {
        BaHalo(modifier, size)
    }
}

/* ---- BA 运行页 ---- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BaHome(cfg: AppConfig, bot: ShopAccessibilityService.BotState, connected: Boolean) {
    val context = LocalContext.current
    val startText = stringResource(R.string.start)
    val restartText = stringResource(R.string.restart_btn)
    // 诊断入口（与 Steam 主题功能对等）：点击版本号 = 抓图 / 长按 = 识别基准
    val version = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" }
        catch (e: Exception) { "" }
    }
    val diagRawText = stringResource(R.string.diag_raw_capture)
    val diagBenchText = stringResource(R.string.diag_benchmark_done)
    val svcMissingText = stringResource(R.string.svc_not_enabled)
    val stageRes = if (connected) {
        when (bot.stage) {
            ShopAccessibilityService.Stage.IDLE -> R.string.not_running
            ShopAccessibilityService.Stage.CHECKING -> R.string.checking
            ShopAccessibilityService.Stage.BUYING -> R.string.buying
            ShopAccessibilityService.Stage.REFRESHING -> R.string.refreshing
            ShopAccessibilityService.Stage.RESTING -> R.string.resting
            ShopAccessibilityService.Stage.PAUSED -> R.string.paused
            ShopAccessibilityService.Stage.WAITING -> R.string.waiting_game
        }
    } else R.string.svc_not_enabled
    val stageText = stringResource(stageRes)
    val stageColor = when {
        !bot.running -> Color(0xFF9FB6CE)
        bot.stage == ShopAccessibilityService.Stage.PAUSED -> Color(0xFFF0A020)
        bot.stage == ShopAccessibilityService.Stage.BUYING -> BaPink
        else -> BaCyan
    }
    val labels = listOf(
        stringResource(R.string.bookmarks) to bot.bookmarksGot.toString(),
        stringResource(R.string.mystic_medals) to bot.medalsGot.toString(),
        stringResource(R.string.refreshes) to bot.refreshes.toString(),
        stringResource(R.string.sky_spent) to bot.skystonesSpent.toString(),
        stringResource(R.string.gold_spent) to RecordStore.fmtNum(bot.goldSpent),
        stringResource(R.string.run_time) to
            RecordStore.fmtMs(if (bot.startedAt > 0) System.currentTimeMillis() - bot.startedAt else 0)
    )
    // 仪表盘外环缓慢旋转动效
    val ringRotate = rememberInfiniteTransition(label = "ba-ring")
    val ringAngle by ringRotate.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "ba-ring-angle"
    )

    Box(Modifier.fillMaxSize()) {
        HalftoneDots(
            modifier = Modifier.fillMaxSize(),
            color = BaPink,
            alpha = 0.05f
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // 顶部横幅：渐变 + 斜条纹 + 状态 + 小美少女装饰
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.linearGradient(listOf(BaCyan, Color(0xFF5BB2F2))))
            ) {
                BannerStripes(Modifier.fillMaxWidth().height(110.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "✦ E7SA ✦",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                            Spacer(Modifier.width(8.dp))
                            // 诊断入口（与 Steam 主题对等）：点击=抓图 / 长按=识别基准
                            Text(
                                "v$version",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.75f),
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        val s = ShopAccessibilityService.instance
                                        if (s != null) {
                                            Thread { s.debugCaptureRaw(20) }.start()
                                            toast(context, diagRawText)
                                        } else toast(context, svcMissingText)
                                    },
                                    onLongClick = {
                                        val s = ShopAccessibilityService.instance
                                        if (s != null) {
                                            Thread { s.debugRunBenchmark() }.start()
                                            toast(context, diagBenchText)
                                        } else toast(context, svcMissingText)
                                    }
                                )
                            )
                        }
                        Text(
                            stageText,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        if (!bot.running && bot.lastError.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.last_stop_prefix, bot.lastError),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFE0DC)
                            )
                        }
                    }
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center
                    ) {
                        BaCharSlot(Modifier.size(62.dp), slot = "ba_char_home.png")
                    }
                }
            }

            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 状态仪表盘：旋转虚线外环 + 色环 + 星标
                BaFrameCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Box(contentAlignment = Alignment.Center) {
                            // 旋转虚线环
                            Canvas(Modifier.size(150.dp).rotate(ringAngle)) {
                                val radius = size.minDimension / 2f - 6.dp.toPx()
                                drawCircle(
                                    color = stageColor.copy(alpha = 0.35f),
                                    radius = radius,
                                    center = center,
                                    style = Stroke(width = 3.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 10.dp.toPx())))
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(112.dp)
                                    .clip(CircleShape)
                                    .background(stageColor.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(86.dp)
                                        .clip(CircleShape)
                                        .background(stageColor.copy(alpha = 0.22f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        when {
                                            !bot.running -> "☆"
                                            bot.stage == ShopAccessibilityService.Stage.BUYING -> "★"
                                            bot.paused -> "◐"
                                            else -> "✦"
                                        },
                                        style = MaterialTheme.typography.headlineLarge,
                                        color = stageColor
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (bot.running) restartText else startText,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 统计成绩单：2 列白卡
                labels.chunked(2).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowItems.forEach { (label, value) ->
                            BaCardShell(modifier = Modifier.weight(1f)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text(label, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        repeat(2 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                }

                // 引擎选择：整卡点选，选中带星标（切换即时刷新）
                Text(
                    stringResource(R.string.ocr_engine),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                var engineSel by remember { mutableStateOf(cfg.ocrEngine) }
                val engines = listOf(
                    "yolo" to stringResource(R.string.ocr_engine_yolo),
                    "traditional" to stringResource(R.string.ocr_engine_traditional)
                )
                engines.forEach { (value, label) ->
                    val selected = engineSel == value || (value == "traditional" &&
                        engineSel !in listOf("yolo", "traditional"))
                    BaCardShell(selected = selected) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    engineSel = value
                                    cfg.ocrEngine = value
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (selected) "★" else "☆",
                                color = if (selected) BaPink else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                if (engineSel == "yolo" && !com.e7.shop.bot.YoloDet.loaded) {
                    Text(
                        stringResource(R.string.engine_yolo_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // 点击逻辑（与识图引擎正交：传统点击 / AI 点击；切换即时刷新）
                Text(
                    stringResource(R.string.click_logic),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                var clickSel by remember { mutableStateOf(cfg.clickLogic) }
                listOf(
                    "traditional" to stringResource(R.string.click_traditional),
                    "ai" to stringResource(R.string.click_ai)
                ).forEach { (value, label) ->
                    val sel = clickSel == value
                    BaCardShell(selected = sel) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clickSel = value
                                    cfg.clickLogic = value
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (sel) "★" else "☆",
                                color = if (sel) BaPink else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                Text(
                    if (clickSel == "ai") stringResource(R.string.click_ai_desc)
                    else stringResource(R.string.click_traditional_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (clickSel == "ai") {
                    Text(
                        stringResource(R.string.click_ai_engine_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (clickSel == "ai" && !com.e7.shop.bot.YoloDet.loaded) {
                    Text(
                        stringResource(R.string.err_ai_yolo_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // 底部固定操作条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Button(
                    onClick = {
                        val svc = ShopAccessibilityService.instance
                        if (svc == null) {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } else {
                            svc.startBot(0, 0)
                        }
                    },
                    modifier = Modifier.weight(2f),
                    shape = BaShape
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (bot.running) restartText else startText)
                }
                OutlinedButton(
                    onClick = {
                        val svc = ShopAccessibilityService.instance ?: return@OutlinedButton
                        if (svc.state.paused) svc.resumeBot() else svc.pauseBot()
                    },
                    enabled = connected && bot.running,
                    modifier = Modifier.weight(1f),
                    shape = BaShape
                ) {
                    Icon(if (bot.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = null, Modifier.size(18.dp))
                }
                OutlinedButton(
                    onClick = { ShopAccessibilityService.instance?.stopBot() },
                    enabled = connected && bot.running,
                    modifier = Modifier.weight(1f),
                    shape = BaShape
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, Modifier.size(18.dp))
                }
            }
        }
    }
}

/* ---- BA 记录页 ---- */

@Composable
private fun BaRecords(cfg: AppConfig) {
    val context = LocalContext.current
    val records = remember { RecordStore(context) }
    var totals by remember { mutableStateOf(records.loadTotals()) }
    var history by remember { mutableStateOf(records.recentSessions(8)) }
    var syncMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun reload() {
        totals = records.loadTotals()
        history = records.recentSessions(8)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BaFrameCard {
            Text(stringResource(R.string.rec_totals),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            val rows = listOf(
                listOf(
                    stringResource(R.string.bookmarks) to totals.bookmarksGot.toString(),
                    stringResource(R.string.mystic_medals) to totals.medalsGot.toString()
                ),
                listOf(
                    stringResource(R.string.refreshes) to totals.refreshes.toString(),
                    stringResource(R.string.sky_spent) to totals.skystonesSpent.toString()
                ),
                listOf(
                    stringResource(R.string.gold_spent) to RecordStore.fmtNum(totals.goldSpent),
                    stringResource(R.string.total_runtime) to RecordStore.fmtMs(totals.runMs)
                ),
                listOf(
                    stringResource(R.string.runs) to totals.runs.toString(),
                    stringResource(R.string.today_runtime) to RecordStore.fmtMs(totals.todayMs)
                )
            )
            rows.forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    r.forEach { (l, v) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(v, fontWeight = FontWeight.Bold)
                            Text(l, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        BaFrameCard {
            Text(stringResource(R.string.rec_recent8),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (history.isEmpty()) stringResource(R.string.no_history)
                else history.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        BaFrameCard {
            Text(stringResource(R.string.wd_sync_title),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch { syncMsg = WebDavSync(cfg, context).upload(records.exportJson()).message }
                }, modifier = Modifier.weight(1f), shape = BaShape) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.wd_upload))
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        val r = WebDavSync(cfg, context).download()
                        if (r.ok && r.data != null && records.importJson(r.data)) reload()
                        syncMsg = r.message
                    }
                }, modifier = Modifier.weight(1f), shape = BaShape) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.wd_download))
                }
            }
            if (syncMsg.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(syncMsg, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/* ---- BA 我的页（含更新日志入口，与 Steam 功能一致） ---- */

@Composable
private fun BaProfile(cfg: AppConfig) {
    val context = LocalContext.current
    var floatyOn by remember { mutableStateOf(cfg.floatyEnabled) }
    var splashOn by remember { mutableStateOf(cfg.showSplash) }
    var showChangeLog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BaFrameCard {
            // 学园手册头：角色素材位 + 应用名
            Row(verticalAlignment = Alignment.CenterVertically) {
                BaCharSlot(Modifier.size(52.dp), slot = "ba_char_profile.png")
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.float_deck_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { context.startActivity(Intent(context, com.e7.shop.score.EquipmentScoreActivity::class.java)) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.score_title),
                    style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showChangeLog = true }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, tint = BaPink)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.changelog),
                    style = MaterialTheme.typography.bodyLarge)
            }
        }
        BaFrameCard {
            Text(stringResource(R.string.general),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.language), Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "system" to stringResource(R.string.lang_system),
                        "zh" to stringResource(R.string.lang_chinese),
                        "en" to stringResource(R.string.lang_english)
                    ).forEach { (v, label) ->
                        FilterChip(
                            selected = cfg.appLanguage == v,
                            onClick = {
                                cfg.appLanguage = v
                                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                                    when (v) {
                                        "zh" -> androidx.core.os.LocaleListCompat.forLanguageTags("zh-CN")
                                        "en" -> androidx.core.os.LocaleListCompat.forLanguageTags("en")
                                        else -> androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                                    }
                                )
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.floaty_title), Modifier.weight(1f))
                Switch(checked = floatyOn, onCheckedChange = { c ->
                    floatyOn = c
                    cfg.floatyEnabled = c
                    if (c && !Settings.canDrawOverlays(context)) {
                        try {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        } catch (e: Exception) {
                            // 部分 ROM 无此设置页：忽略即可，用户仍可在系统设置里手动授权
                            android.util.Log.w("E7SA.UI", "overlay settings unavailable: " + e.message)
                        }
                    }
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.show_splash), Modifier.weight(1f))
                Switch(checked = splashOn, onCheckedChange = { splashOn = it; cfg.showSplash = it })
            }
            Spacer(Modifier.height(10.dp))
            // 切换动画函数（《冰与火之舞》的 DOTween 缓动族）
            EasingPicker(cfg)
            Spacer(Modifier.height(10.dp))
            // 运行日志详细度（写文件，过夜挂机后仍可查证）
            LogLevelPicker(cfg)
        }

        ShizukuCard()
    }

    // 全屏更新日志（与 Steam 主题共享同一组件）
    ChangelogDialog(showChangeLog) { showChangeLog = false }
}

/* ---- BA 设置页（渲染同一份 Schema：功能与 Steam 完全一致，只是呈现不同） ---- */

@Composable
private fun BaSettings(cfg: AppConfig, onChangeAppearance: (String) -> Unit) {
    val context = LocalContext.current
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // 持久化读权限失败：本次选择仍生效，只是重启后可能需重新选图
                android.util.Log.w("E7SA.UI", "persist uri permission failed: " + e.message)
            }
            cfg.bgImage = uri.toString()
        }
    }
    val logoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // 持久化读权限失败：本次选择仍生效，只是重启后可能需重新选图
                android.util.Log.w("E7SA.UI", "persist uri permission failed: " + e.message)
            }
            cfg.customLogoPath = uri.toString()
            cfg.logoMode = "custom"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 外观（主题选择）
        BaFrameCard {
            Text(stringResource(R.string.appearance),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            ThemeRegistry.all.forEach { theme ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            cfg.appearance = theme.id
                            onChangeAppearance(theme.id)
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = cfg.appearance == theme.id ||
                            (theme.id == "dark" && cfg.appearance !in listOf("oled", "ba", "bluearchive")),
                        onClick = {
                            cfg.appearance = theme.id
                            onChangeAppearance(theme.id)
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(theme.labelRes))
                }
            }
        }

        // 自定义外观（背景/Logo）——与 Steam 功能一致
        BaFrameCard {
            Text(stringResource(R.string.appearance),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { bgLauncher.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f), shape = BaShape) {
                    Text(stringResource(R.string.bg_pick))
                }
                OutlinedButton(onClick = { cfg.bgImage = "" }, shape = BaShape) {
                    Text(stringResource(R.string.clear))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.logo_title), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = cfg.logoMode != "custom",
                    onClick = { cfg.logoMode = "official" },
                    label = { Text(stringResource(R.string.logo_official)) }
                )
                FilterChip(
                    selected = cfg.logoMode == "custom",
                    onClick = { logoLauncher.launch(arrayOf("image/*")) },
                    label = { Text(stringResource(R.string.logo_custom)) }
                )
            }
        }

        // 全部设置分组（同一份 Schema，功能完整）
        SettingsSchema.sections(cfg).forEach { section ->
            BaFrameCard {
                Text(stringResource(section.titleRes),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                section.items.forEach { item ->
                    when (item) {
                        is SettingItem.Switch -> BaSwitchRow(
                            stringResource(item.labelRes), item.get()
                        ) { item.set(it) }
                        is SettingItem.Number -> BaNumField(
                            stringResource(item.labelRes), item.get().toString()
                        ) { item.set(it) }
                        is SettingItem.Text -> BaTextField(
                            stringResource(item.labelRes), item.get(), item.isPassword
                        ) { item.set(it) }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun BaSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun BaNumField(label: String, initial: String, onValue: (Long) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            val filtered = t.filter { it.isDigit() }.take(9)
            text = filtered
            filtered.toLongOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        shape = BaShape,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun BaTextField(label: String, initial: String, isPassword: Boolean, onValue: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            onValue(t)
        },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        shape = BaShape,
        modifier = Modifier.fillMaxWidth()
    )
}
