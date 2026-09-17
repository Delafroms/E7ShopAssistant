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

/* ============================================================================
 * Steam 设计语言（客户端控制台）
 * ========================================================================== */

/* ---- Steam 品牌种子色 ----
 * 组件（StEngineRow 选中态 / 状态指示色 / PLAY 键渐变）直接引用这些常量，
 * 因此**保留名称与语义**，只按 Material 3 暗色规范重新取值（tone 80 级别，降饱和）。
 *
 * 注意 SGreen 仍保持鲜艳：它用于"购买中"状态与 PLAY 键，需要醒目；
 * 大面积铺开的绿由 tertiary（STertiary，降饱和）承担。
 */
private val SBlue = Color(0xFF8ECDF5)      // M3 暗色 primary（原 #66C0F4 过饱和、刺眼）
private val SBlueDeep = Color(0xFF004C6E)  // primaryContainer
private val SGreen = Color(0xFFA4D007)     // 状态绿 / PLAY：保持鲜艳
private val SDeep = Color(0xFF0A0E13)      // 最深背景
private val SGold = Color(0xFFE8C56A)      // 状态金（原 #D4AF37 略暗）
private val SError = Color(0xFFFFB4AB)     // M3 暗色 error
private val STertiary = Color(0xFFC3D98A)  // 大面积绿（降饱和）

/**
 * Steam 暗色配色（Material 3 **完整角色**）。
 *
 * 为什么重写：旧版只填了 primary/secondary/background/surface/surfaceVariant/outline/error
 * 七个角色，**完全没有 surfaceContainer 色阶** —— 于是组件只能用 1dp 描边来表达层次，
 * 这正是"看起来不像 Material"的根因。现在补齐 M3 全套角色，层次改用 tonal elevation。
 */
private val SteamScheme = darkColorScheme(
    // Primary：Steam 链接蓝
    primary = SBlue,
    onPrimary = Color(0xFF00344D),
    primaryContainer = SBlueDeep,
    onPrimaryContainer = Color(0xFFC9E6FF),
    // Secondary：蓝灰
    secondary = Color(0xFFB0C8DC),
    onSecondary = Color(0xFF1B3143),
    secondaryContainer = Color(0xFF324759),
    onSecondaryContainer = Color(0xFFCCE4F8),
    // Tertiary：降饱和的绿（大面积使用不刺眼）
    tertiary = STertiary,
    onTertiary = Color(0xFF2A3400),
    tertiaryContainer = Color(0xFF3F4C00),
    onTertiaryContainer = Color(0xFFDFF5A4),
    // Error
    error = SError,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    // Surface 色阶：保留 Steam 的深蓝调，同时给出 M3 的五级层次
    background = SDeep,
    onBackground = Color(0xFFE0E5EB),
    surface = Color(0xFF121820),
    onSurface = Color(0xFFE0E5EB),
    surfaceVariant = Color(0xFF3A434D),
    onSurfaceVariant = Color(0xFFC0C8D2),
    surfaceContainerLowest = Color(0xFF0A0E13),
    surfaceContainerLow = Color(0xFF121820),
    surfaceContainer = Color(0xFF171F29),
    surfaceContainerHigh = Color(0xFF1E2732),
    surfaceContainerHighest = Color(0xFF26313D),
    outline = Color(0xFF8A929C),
    outlineVariant = Color(0xFF3A434D),
    inverseSurface = Color(0xFFE0E5EB),
    inverseOnSurface = Color(0xFF2C3138),
    inversePrimary = Color(0xFF00639B),
    scrim = Color(0xFF000000),
    surfaceTint = SBlue
)

private val SteamOledScheme = darkColorScheme(
    primary = SBlue,
    onPrimary = Color(0xFF00344D),
    primaryContainer = Color(0xFF101014),
    onPrimaryContainer = Color(0xFFC9E6FF),
    secondary = Color(0xFFB0C8DC),
    onSecondary = Color(0xFF1B3143),
    secondaryContainer = Color(0xFF101418),
    onSecondaryContainer = Color(0xFFCCE4F8),
    tertiary = STertiary,
    onTertiary = Color(0xFF2A3400),
    tertiaryContainer = Color(0xFF14180A),
    onTertiaryContainer = Color(0xFFDFF5A4),
    error = SError,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF3A0A0A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color.Black,
    onBackground = Color(0xFFE0E5EB),
    surface = Color.Black,
    onSurface = Color(0xFFE0E5EB),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFB8C0CA),
    // OLED 的层次靠"极暗灰阶"表达（纯黑上再加黑会看不出来）
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF080808),
    surfaceContainer = Color(0xFF0E0E0E),
    surfaceContainerHigh = Color(0xFF151515),
    surfaceContainerHighest = Color(0xFF1D1D1D),
    outline = Color(0xFF7A828C),
    outlineVariant = Color(0xFF262626),
    inverseSurface = Color(0xFFE0E5EB),
    inverseOnSurface = Color(0xFF1A1A1A),
    inversePrimary = Color(0xFF00639B),
    scrim = Color(0xFF000000),
    surfaceTint = SBlue
)

/* ---- Material 3 形状系统 ----
 * 旧版在组件里各处硬编码 6dp / 8dp / 22dp，圆角语言不统一。
 * 这里按 M3 规范统一声明，组件一律取 MaterialTheme.shapes.*。
 */
private val E7Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

object SteamTheme : E7Theme {
    override val id = "dark"
    override val labelRes = R.string.appearance_dark
    @Composable override fun ColorSchemeProvider(content: @Composable () -> Unit) =
        MaterialTheme(colorScheme = SteamScheme, shapes = E7Shapes, content = content)
    @Composable override fun HomeScreen(cfg: AppConfig, bot: ShopAccessibilityService.BotState, connected: Boolean) =
        StHome(cfg, bot, connected, flat = false)
    @Composable override fun RecordsScreen(cfg: AppConfig) = StRecords(cfg)
    @Composable override fun ProfileScreen(cfg: AppConfig) = StProfile(cfg)
    @Composable override fun SettingsScreen(cfg: AppConfig, onChangeAppearance: (String) -> Unit) =
        StSettings(cfg, onChangeAppearance)
}

object SteamOledTheme : E7Theme {
    override val id = "oled"
    override val labelRes = R.string.appearance_oled
    @Composable override fun ColorSchemeProvider(content: @Composable () -> Unit) =
        MaterialTheme(colorScheme = SteamOledScheme, shapes = E7Shapes, content = content)
    @Composable override fun HomeScreen(cfg: AppConfig, bot: ShopAccessibilityService.BotState, connected: Boolean) =
        StHome(cfg, bot, connected, flat = true)
    @Composable override fun RecordsScreen(cfg: AppConfig) = StRecords(cfg)
    @Composable override fun ProfileScreen(cfg: AppConfig) = StProfile(cfg)
    @Composable override fun SettingsScreen(cfg: AppConfig, onChangeAppearance: (String) -> Unit) =
        StSettings(cfg, onChangeAppearance)
}

/* ---- Steam 组件 ---- */

/**
 * Steam 面板（Material 3 卡片语义）。
 *
 * 旧版是 `surface 底色 + 1dp 描边`——那是拟物/扁平风格；Material 3 用**色调层级**
 * （surfaceContainer 色阶）表达层次，边框只属于 outline 的职责。改用 surfaceContainerLow + shapes.medium。
 */
@Composable
private fun StPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** Steam 大写分区标题。 */
@Composable
private fun StLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.2.sp
    )
}

/** Steam 统计块：M3 色调瓦片（surfaceContainerHigh）+ 品牌蓝数值 + 大写小标签。 */
@Composable
private fun StTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 0.4.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 引擎选择行（M3 选中语义）。
 *
 * 旧版用"蓝色描边 + 深蓝底"表达选中；描边在 M3 里属于 outline 的职责，不用于选中态。
 * 改用 secondaryContainer（M3 标准选中容器色）+ onSecondaryContainer 文本色，
 * 未选中态用 surfaceContainerHigh。
 */
@Composable
private fun StEngineRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/* ---- Steam 运行页 ---- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StHome(cfg: AppConfig, bot: ShopAccessibilityService.BotState, connected: Boolean, flat: Boolean) {
    val context = LocalContext.current
    var showRisk by remember { mutableStateOf(false) }
    var showShot by remember { mutableStateOf(false) }
    var logoVersion by remember { mutableStateOf(0) }
    val logoBmp = remember(logoVersion) { loadLogoBitmap(context, cfg) }
    val bgBmp = remember { loadBgBitmap(context, cfg) }
    // 预取字符串（供非 Composable lambda 使用）
    val diagRawText = stringResource(R.string.diag_raw_capture)
    val diagBenchText = stringResource(R.string.diag_benchmark_done)
    val svcMissingText = stringResource(R.string.svc_not_enabled)
    val toastStartedText = stringResource(R.string.toast_started)
    val toastResumedText = stringResource(R.string.toast_resumed)
    val toastPausedText = stringResource(R.string.toast_paused)
    val toastStoppingText = stringResource(R.string.toast_stopping)
    val toastEnableText = stringResource(R.string.toast_enable_accessibility)
    val version = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" }
        catch (e: Exception) { "" }
    }
    val startText = stringResource(R.string.start)
    val restartText = stringResource(R.string.restart_btn)
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
        !connected -> MaterialTheme.colorScheme.error
        !bot.running -> MaterialTheme.colorScheme.onSurfaceVariant
        bot.stage == ShopAccessibilityService.Stage.PAUSED -> SGold
        bot.stage == ShopAccessibilityService.Stage.BUYING -> SGreen
        else -> SBlue
    }
    // 运行状态点脉冲（Steam 风格呼吸灯）
    val pulse = rememberInfiniteTransition(label = "st-pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850, easing = LinearEasing), RepeatMode.Reverse),
        label = "st-pulse-a"
    )
    val stats = listOf(
        stringResource(R.string.bookmarks) to bot.bookmarksGot.toString(),
        stringResource(R.string.mystic_medals) to bot.medalsGot.toString(),
        stringResource(R.string.refreshes) to bot.refreshes.toString(),
        stringResource(R.string.sky_spent) to bot.skystonesSpent.toString(),
        stringResource(R.string.gold_spent) to RecordStore.fmtNum(bot.goldSpent),
        stringResource(R.string.run_time) to
            RecordStore.fmtMs(if (bot.startedAt > 0) System.currentTimeMillis() - bot.startedAt else 0)
    )

    Box(Modifier.fillMaxSize()) {
        bgBmp?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // Steam 客户端头图：深蓝渐变（OLED 平面）+ 品牌蓝下划线
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (flat) Brush.linearGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface))
                        else Brush.linearGradient(listOf(SBlueDeep, SDeep))
                    )
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    logoBmp?.let {
                        Image(it.asImageBitmap(), contentDescription = "logo", modifier = Modifier.height(44.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "v$version",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    Thread {
                                        val json = s.debugRunBenchmark()
                                        android.util.Log.i("E7SA.Benchmark", json)
                                    }.start()
                                    toast(context, diagBenchText)
                                } else toast(context, svcMissingText)
                            }
                        )
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(SBlue))

            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 会话状态
                StPanel {
                    StLabel(stringResource(R.string.session_status))
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(stageColor.copy(alpha = if (bot.running) pulseAlpha else 0.9f))
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stageText,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = stageColor
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (!bot.running && bot.lastError.isNotEmpty()) {
                        Text(
                            stringResource(R.string.last_stop_prefix, bot.lastError),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (!bot.running) {
                        Text(
                            stringResource(R.string.home_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 会话统计 3×2 仪表块
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    stats.chunked(3).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowItems.forEach { (label, value) -> StTile(label, value, Modifier.weight(1f)) }
                            repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                // 控制区：Steam 绿 PLAY 主按钮（开始=重启语义）+ 双键
                StPanel {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (flat) Brush.linearGradient(listOf(SGreen, SGreen))
                                else Brush.linearGradient(listOf(Color(0xFF75B022), Color(0xFF4F7A10)))
                            )
                            .clickable {
                                val svc = ShopAccessibilityService.instance
                                if (svc == null) {
                                    toast(context, toastEnableText)
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                } else if (!cfg.riskAccepted) {
                                    showRisk = true
                                } else {
                                    val msg = svc.startBot(0, 0)
                                    toast(context, if (msg == "ok") toastStartedText else msg)
                                }
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color(0xFF152000),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                (if (bot.running) restartText else startText).uppercase(),
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp,
                                color = Color(0xFF152000)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val svc = ShopAccessibilityService.instance ?: return@OutlinedButton
                                if (svc.state.paused) {
                                    svc.resumeBot(); toast(context, toastResumedText)
                                } else {
                                    svc.pauseBot(); toast(context, toastPausedText)
                                }
                            },
                            enabled = connected && bot.running,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                if (bot.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                contentDescription = null,
                                Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (bot.paused) stringResource(R.string.resume_btn) else stringResource(R.string.pause))
                        }
                        OutlinedButton(
                            onClick = {
                                ShopAccessibilityService.instance?.stopBot()
                                toast(context, toastStoppingText)
                            },
                            enabled = connected && bot.running,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.stop))
                        }
                    }
                }

                // 引擎（双引擎平等，选中行高亮；切换即时刷新）
                StPanel {
                    StLabel(stringResource(R.string.ocr_engine))
                    Spacer(Modifier.height(8.dp))
                    var engineSel by remember { mutableStateOf(cfg.ocrEngine) }
                    val yoloLoaded = com.e7.shop.bot.YoloDet.loaded
                    listOf(
                        "yolo" to stringResource(R.string.ocr_engine_yolo),
                        "traditional" to stringResource(R.string.ocr_engine_traditional)
                    ).forEach { (value, label) ->
                        StEngineRow(
                            selected = engineSel == value || (value == "traditional" &&
                                engineSel !in listOf("yolo", "traditional")),
                            label = label
                        ) {
                            engineSel = value
                            cfg.ocrEngine = value
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (engineSel == "yolo" && !yoloLoaded) {
                        Text(
                            stringResource(R.string.engine_yolo_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            if (engineSel == "yolo") stringResource(R.string.engine_yolo_desc)
                            else stringResource(R.string.engine_trad_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 点击逻辑（与识图引擎正交：传统点击 / AI 点击；切换即时刷新）
                StPanel {
                    StLabel(stringResource(R.string.click_logic))
                    Spacer(Modifier.height(8.dp))
                    var clickSel by remember { mutableStateOf(cfg.clickLogic) }
                    listOf(
                        "traditional" to stringResource(R.string.click_traditional),
                        "ai" to stringResource(R.string.click_ai)
                    ).forEach { (value, label) ->
                        StEngineRow(selected = clickSel == value, label = label) {
                            clickSel = value
                            cfg.clickLogic = value
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        if (clickSel == "ai") stringResource(R.string.click_ai_desc)
                        else stringResource(R.string.click_traditional_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (clickSel == "ai") {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.click_ai_engine_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (clickSel == "ai" && !com.e7.shop.bot.YoloDet.loaded) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.err_ai_yolo_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // 诊断入口
                OutlinedButton(onClick = { showShot = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.view_shot))
                }
            }
        }
    }

    if (showRisk) {
        AlertDialog(
            onDismissRequest = { showRisk = false },
            title = { Text(stringResource(R.string.dlg_risk_title)) },
            text = { Text(stringResource(R.string.dlg_risk_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    cfg.riskAccepted = true
                    showRisk = false
                    val svc = ShopAccessibilityService.instance
                    if (svc != null) {
                        val msg = svc.startBot(0, 0)
                        toast(context, if (msg == "ok") toastStartedText else msg)
                    }
                }) { Text(stringResource(R.string.dlg_accept_risk)) }
            },
            dismissButton = {
                TextButton(onClick = { showRisk = false }) { Text(stringResource(R.string.dlg_cancel)) }
            }
        )
    }

    if (showShot) {
        val svc = ShopAccessibilityService.instance
        val bmp = remember {
            svc?.let { decodeFileSafe(java.io.File(it.filesDir, "debug_last.png")) }
        }
        AlertDialog(
            onDismissRequest = { showShot = false },
            title = { Text(stringResource(R.string.shot_dialog_title)) },
            text = {
                if (bmp != null) {
                    Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth())
                } else Text(stringResource(R.string.shot_empty))
            },
            confirmButton = { TextButton(onClick = { showShot = false }) { Text(stringResource(R.string.float_close)) } }
        )
    }
}

/* ---- Steam 记录页 ---- */

@Composable
private fun StRecords(cfg: AppConfig) {
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
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StPanel {
            StLabel(stringResource(R.string.rec_totals))
            Spacer(Modifier.height(10.dp))
            val rows = listOf(
                listOf(
                    stringResource(R.string.bookmarks) to totals.bookmarksGot.toString(),
                    stringResource(R.string.mystic_medals) to totals.medalsGot.toString(),
                    stringResource(R.string.refreshes) to totals.refreshes.toString()
                ),
                listOf(
                    stringResource(R.string.sky_spent) to totals.skystonesSpent.toString(),
                    stringResource(R.string.gold_spent) to RecordStore.fmtNum(totals.goldSpent),
                    stringResource(R.string.total_runtime) to RecordStore.fmtMs(totals.runMs)
                ),
                listOf(
                    stringResource(R.string.today_runtime) to RecordStore.fmtMs(totals.todayMs),
                    stringResource(R.string.runs) to totals.runs.toString(),
                    "" to ""
                )
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems.forEach { (label, value) ->
                            if (label.isNotEmpty()) StTile(label, value, Modifier.weight(1f))
                            else Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        StPanel {
            StLabel(stringResource(R.string.rec_recent8))
            Spacer(Modifier.height(8.dp))
            Text(
                if (history.isEmpty()) stringResource(R.string.no_history) else history.joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StPanel {
            StLabel(stringResource(R.string.wd_sync_title))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch { syncMsg = WebDavSync(cfg, context).upload(records.exportJson()).message }
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.wd_upload))
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        val r = WebDavSync(cfg, context).download()
                        if (r.ok && r.data != null) {
                            if (records.importJson(r.data)) reload()
                        }
                        syncMsg = r.message
                    }
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.wd_download))
                }
                OutlinedButton(onClick = {
                    scope.launch { syncMsg = WebDavSync(cfg, context).testConnection().message }
                }) {
                    Text(stringResource(R.string.wd_test))
                }
            }
            if (syncMsg.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(syncMsg, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/* ---- Steam 我的页 ---- */

@Composable
private fun StProfile(cfg: AppConfig) {
    val context = LocalContext.current
    var showChangeLog by remember { mutableStateOf(false) }
    var floatyOn by remember { mutableStateOf(cfg.floatyEnabled) }
    var splashOn by remember { mutableStateOf(cfg.showSplash) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StPanel {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        context.startActivity(Intent(context, com.e7.shop.score.EquipmentScoreActivity::class.java))
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.score_title), style = MaterialTheme.typography.bodyLarge)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showChangeLog = true }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.changelog), style = MaterialTheme.typography.bodyLarge)
            }
        }

        StPanel {
            StLabel(stringResource(R.string.general))
            Spacer(Modifier.height(10.dp))
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
            Spacer(Modifier.height(10.dp))
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
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.show_splash), Modifier.weight(1f))
                Switch(checked = splashOn, onCheckedChange = { c ->
                    splashOn = c
                    cfg.showSplash = c
                })
            }
            Spacer(Modifier.height(10.dp))
            // 切换动画函数（《冰与火之舞》的 DOTween 缓动族）
            EasingPicker(cfg)
            Spacer(Modifier.height(10.dp))
            // 运行日志详细度（写文件，过夜挂机后仍可查证）
            LogLevelPicker(cfg)
        }

        // Shizuku 快捷授权（未安装时自动隐藏）
        ShizukuCard()
    }

    // 全屏更新日志（与 BA 主题共享同一组件）
    ChangelogDialog(showChangeLog) { showChangeLog = false }
}

/* ---- Steam 设置页 ---- */

@Composable
private fun StSettings(cfg: AppConfig, onChangeAppearance: (String) -> Unit) {
    val context = LocalContext.current
    var appearance by remember { mutableStateOf(cfg.appearance) }
    var bgVersion by remember { mutableStateOf(0) }
    var logoVersion by remember { mutableStateOf(0) }
    var logoMode by remember { mutableStateOf(cfg.logoMode) }

    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // 持久化读权限失败：本次选择仍生效，只是重启后可能需重新选图
                android.util.Log.w("E7SA.UI", "persist uri permission failed: " + e.message)
            }
            cfg.bgImage = uri.toString()
            bgVersion++
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
            logoMode = "custom"
            logoVersion++
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 外观：三套主题 + 色板样块
        StPanel {
            StLabel(stringResource(R.string.appearance))
            Spacer(Modifier.height(10.dp))
            ThemeRegistry.all.forEach { theme ->
                StAppearanceRow(
                    label = stringResource(theme.labelRes),
                    swatch = when (theme.id) {
                        "oled" -> Color.Black
                        "ba", "bluearchive" -> Color(0xFFFF7FB6)
                        else -> Color(0xFF1B2838)
                    },
                    selected = appearance == theme.id ||
                        (theme.id == "dark" && appearance !in listOf("oled", "ba", "bluearchive"))
                ) {
                    appearance = theme.id
                    onChangeAppearance(theme.id)
                }
            }
        }

        // 全部设置分组（Schema 驱动：主题只决定"怎么呈现"，Schema 决定"有什么"）
        SettingsSchema.sections(cfg).forEach { section ->
            StPanel {
                StLabel(stringResource(section.titleRes))
                Spacer(Modifier.height(10.dp))
                section.items.forEach { item ->
                    when (item) {
                        is SettingItem.Switch ->
                            StSwitchRow(stringResource(item.labelRes), item.get()) { item.set(it) }
                        is SettingItem.Number ->
                            StNumField(stringResource(item.labelRes), item.get().toString()) { item.set(it) }
                        is SettingItem.Text ->
                            StTextField(stringResource(item.labelRes), item.get(), item.isPassword) { item.set(it) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // 自定义外观（背景 / Logo）
        StPanel {
            StLabel(stringResource(R.string.appearance))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { bgLauncher.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.bg_pick))
                }
                OutlinedButton(onClick = { cfg.bgImage = ""; bgVersion++ }) {
                    Text(stringResource(R.string.clear))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.logo_title), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = logoMode == "official",
                    onClick = {
                        logoMode = "official"
                        cfg.logoMode = "official"
                        logoVersion++
                    },
                    label = { Text(stringResource(R.string.logo_official)) }
                )
                FilterChip(
                    selected = logoMode == "custom",
                    onClick = { logoLauncher.launch(arrayOf("image/*")) },
                    label = { Text(stringResource(R.string.logo_custom)) }
                )
            }
        }
    }
}

@Composable
private fun StAppearanceRow(label: String, swatch: Color, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(swatch)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 日志详细度 id → 显示名资源。 */
internal fun logLevelLabelRes(id: String): Int = when (id) {
    "off" -> R.string.log_level_off
    "detail" -> R.string.log_level_detail
    "debug" -> R.string.log_level_debug
    else -> R.string.log_level_normal
}

/**
 * 运行日志详细度选择器（两套主题共用）。
 *
 * 日志写文件（Android/data/com.e7.shop/files/e7sa_run.log），不会被 logcat 缓冲区刷掉，
 * 过夜挂机后仍可 adb pull 查证。等级越高日志量越大（精简约 5MB/晚，调试可达 200MB/晚）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LogLevelPicker(cfg: AppConfig) {
    var sel by remember { mutableStateOf(cfg.logLevel) }
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.log_level), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("off", "normal", "detail", "debug").forEach { id ->
                FilterChip(
                    selected = sel == id,
                    onClick = { sel = id; cfg.logLevel = id },
                    label = {
                        Text(
                            stringResource(logLevelLabelRes(id)),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.log_path),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun StSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 动画函数 id → 显示名资源（与 ANIM_EASINGS 一一对应）。 */
internal fun easingLabelRes(id: String): Int = when (id) {
    "quad" -> R.string.anim_easing_quad
    "cubic" -> R.string.anim_easing_cubic
    "quart" -> R.string.anim_easing_quart
    "expo" -> R.string.anim_easing_expo
    "circ" -> R.string.anim_easing_circ
    else -> R.string.anim_easing_beat
}

/**
 * 切换动画函数选择器（两套主题共用）。
 *
 * 选项直接遍历 ui/DfibEasing.kt 的 ANIM_EASINGS —— MainActivity 的 NavHost 取函数
 * 用的是**同一张表**，所以"选了就真的生效"，不会变成假开关。
 * 后五族（quad/cubic/quart/expo/circ）取自《冰与火之舞》所用的 DOTween Ease 枚举。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EasingPicker(cfg: AppConfig) {
    var fam by remember { mutableStateOf(cfg.animEasing) }
    var variant by remember { mutableStateOf(cfg.animEasingVariant) }
    var strength by remember { mutableStateOf(cfg.animEasingStrength) }

    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.anim_easing), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        // ① 缓动族
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ANIM_EASINGS.forEach { (id, _) ->
                FilterChip(
                    selected = fam == id,
                    onClick = { fam = id; cfg.animEasing = id },
                    label = {
                        Text(
                            stringResource(easingLabelRes(id)),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        // ② 方向：In / Out / In-Out
        Text(
            stringResource(R.string.anim_easing_variant),
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                EASING_VARIANT_IN to R.string.anim_easing_in,
                EASING_VARIANT_OUT to R.string.anim_easing_out,
                EASING_VARIANT_INOUT to R.string.anim_easing_inout
            ).forEach { (v, res) ->
                FilterChip(
                    selected = variant == v,
                    onClick = { variant = v; cfg.animEasingVariant = v },
                    label = { Text(stringResource(res), style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        // ③ 强度（与线性插值：0 = 完全线性，100 = 完整缓动）
        Text(
            "${stringResource(R.string.anim_easing_strength)}  $strength%",
            style = MaterialTheme.typography.labelMedium
        )
        Slider(
            value = strength.toFloat(),
            onValueChange = {
                strength = it.toInt()
                cfg.animEasingStrength = it.toInt()
            },
            valueRange = 0f..100f
        )
    }
}

@Composable
internal fun StNumField(label: String, initial: String, onValue: (Long) -> Unit) {
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
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun StTextField(label: String, initial: String, isPassword: Boolean, onValue: (String) -> Unit) {
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
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    )
}

/* ============================================================================
 * Blue Archive · 明亮学院
 * ========================================================================== */

