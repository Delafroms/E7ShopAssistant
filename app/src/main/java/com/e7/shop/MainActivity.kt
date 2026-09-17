package com.e7.shop

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.e7.shop.ui.DfibSnap
import com.e7.shop.ui.animEasingOf
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.e7.shop.ShopAccessibilityService.BotState
import com.e7.shop.ShopAccessibilityService.Stage
import com.e7.shop.data.AppConfig
import com.e7.shop.data.RecordStore
import com.e7.shop.net.WebDavSync
import rikka.shizuku.Shizuku
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 新架构 UI 层：Jetpack Compose + Material 3，全部页面重建。
 *
 * 关键设计：
 *  - 主题 = 单一 State（appearance），切换即全树重组，绝不 recreate()
 *    -> Appearance 切换不再串页面状态（旧 View 体系的根因被从机制上消灭）
 *  - 导航状态由 Navigation Compose 持有（rememberSaveable 语义），与主题无关
 *  - 无大"开始"按钮、无金色 EPIC SEVEN 文字（保留官方/自定义 Logo 图）
 *  - GPU 计算·帧数捕捉仅在 GPU 引擎下条件显示（AnimatedVisibility）
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = AppConfig(this)
        applyLocale(cfg.appLanguage)
        enterImmersiveMode()
        setContent {
            var appearance by rememberSaveable { mutableStateOf(cfg.appearance) }
            val theme = com.e7.shop.ui.ThemeRegistry.forId(appearance)
            theme.ColorSchemeProvider {
                MainScreen(
                    cfg = cfg,
                    theme = theme,
                    onChangeAppearance = { v ->
                        cfg.appearance = v
                        appearance = v   // 主题=状态：换房子式重组而非 recreate
                    }
                )
            }
        }
    }

    private fun applyLocale(lang: String) {
        val locales: LocaleListCompat = when (lang) {
            "zh" -> LocaleListCompat.forLanguageTags("zh-CN")
            "en" -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun enterImmersiveMode() {
        try {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
        } catch (e: Exception) {
            // 沉浸模式只是观感：失败时界面照常可用，不阻断启动
            android.util.Log.w("E7SA.UI", "immersive mode unavailable: " + e.message)
        }
    }
}

/* ============ 主框架（底部导航 + NavHost；页面由主题路由） ============ */

@Composable
private fun MainScreen(cfg: AppConfig, theme: com.e7.shop.ui.E7Theme, onChangeAppearance: (String) -> Unit) {
    val navController = rememberNavController()
    val botState = remember {
        mutableStateOf(ShopAccessibilityService.instance?.state?.copy() ?: BotState())
    }
    var connected by remember { mutableStateOf(ShopAccessibilityService.instance != null) }

    // 服务可能在 Activity 之后连接：轮询接入（连接后开始接收状态）
    LaunchedEffect(Unit) {
        while (!connected) {
            delay(2000)
            connected = ShopAccessibilityService.instance != null
        }
    }
    DisposableEffect(connected) {
        if (connected) {
            val listener: (BotState) -> Unit = { s -> botState.value = s.copy() }
            ShopAccessibilityService.instance?.addStateListener(listener)
            onDispose { ShopAccessibilityService.instance?.removeStateListener(listener) }
        } else onDispose { }
    }
    // 状态同步安全网：每 2 秒轮询服务状态兜底（C7 降频：listener 正常时无需每秒重组一次；
    // 保留轮询是为了杜绝 listener 生命周期失步——曾出现"引擎在跑但 UI 显示未运行"）。
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            ShopAccessibilityService.instance?.let { s ->
                botState.value = s.state.copy()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                val items = listOf(
                    Triple("home", stringResource(R.string.nav_home), Icons.Filled.Home),
                    Triple("records", stringResource(R.string.nav_records), Icons.AutoMirrored.Filled.List),
                    Triple("profile", stringResource(R.string.nav_user), Icons.Filled.Person),
                    Triple("settings", stringResource(R.string.nav_settings), Icons.Filled.Settings)
                )
                val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
                items.forEach { (route, label, icon) ->
                    NavigationBarItem(
                        selected = currentRoute == route,
                        onClick = {
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding),
            // 《冰与火之舞》风格的页面切换（缓动函数见 ui/DfibEasing.kt）：
            // 入场用 DfibBeat 的"快—顿—收"制造节拍感，退场用 DfibSnap 急停干脆。
            // 非线性（过冲/回弹）是这套动画的灵魂 —— 不是匀速平移，而是有打击感地切入。
            enterTransition = {
                slideInHorizontally(
                    animationSpec = tween(
                        340,
                        easing = animEasingOf(cfg.animEasing, cfg.animEasingVariant, cfg.animEasingStrength)
                    ),
                    initialOffsetX = { it / 4 }
                ) + fadeIn(animationSpec = tween(220, easing = DfibSnap))
            },
            exitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(240, easing = DfibSnap),
                    targetOffsetX = { -it / 10 }
                ) + fadeOut(animationSpec = tween(160))
            },
            popEnterTransition = {
                slideInHorizontally(
                    animationSpec = tween(
                        340,
                        easing = animEasingOf(cfg.animEasing, cfg.animEasingVariant, cfg.animEasingStrength)
                    ),
                    initialOffsetX = { -it / 4 }
                ) + fadeIn(animationSpec = tween(220, easing = DfibSnap))
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(240, easing = DfibSnap),
                    targetOffsetX = { it / 10 }
                ) + fadeOut(animationSpec = tween(160))
            }
        ) {
            composable("home") { theme.HomeScreen(cfg, botState.value, connected) }
            composable("records") { theme.RecordsScreen(cfg) }
            composable("profile") { theme.ProfileScreen(cfg) }
            composable("settings") { theme.SettingsScreen(cfg, onChangeAppearance) }
        }
    }
}

/* ============ 更新日志（全屏阅读，主题感知，各主题共享） ============ */

private fun colorHex(c: Color): String =
    String.format("#%02X%02X%02X", (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

/** 全屏更新日志对话框：更大阅读区域 + 主题感知配色（浅色/深色各一套排版）。 */
@Composable
internal fun ChangelogDialog(show: Boolean, onDismiss: () -> Unit) {
    if (!show) return
    val scheme = MaterialTheme.colorScheme
    val isLight = scheme.background.luminance() > 0.5f
    val accentHex = colorHex(if (isLight) scheme.primary else Color(0xFFD4AF37))
    val bgHex = colorHex(scheme.background)
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(18.dp),
            color = scheme.background
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.changelog),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.float_close))
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(scheme.outline)
                )
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            loadDataWithBaseURL(
                                null,
                                MarkdownRenderer.render(Changelog.getMarkdown(), !isLight, accentHex, bgHex),
                                "text/html",
                                "utf-8",
                                null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}



/* ============ 我的页 ============ */

/* ============ 设置页 ============ */

/* ============ 一键开启无障碍（多通道，按可靠性排序） ============ */

private const val SHIZUKU_REQ = 10086

/**
 * 通道一：直接用 WRITE_SECURE_SETTINGS 改系统设置（**首选**）。
 *
 * 需要一次性 adb 授权：
 *   adb shell pm grant com.e7.shop android.permission.WRITE_SECURE_SETTINGS
 * 授权后重启/覆盖安装都保留，**插拔 USB 不会掉**（这正是它优于 Shizuku 的地方）。
 *
 * 注意必须先写 enabled_accessibility_services 再写 accessibility_enabled：
 * 顺序反了系统可能忽略（无障碍总开关关着时，服务列表的变更不生效）。
 */
private fun enableAccessibilityDirect(context: android.content.Context): String {
    return try {
        val cr = context.contentResolver
        val svc = "com.e7.shop/com.e7.shop.ShopAccessibilityService"
        // 保留用户已启用的其他无障碍服务，只追加自己（避免把用户的其他服务关掉）
        val cur = android.provider.Settings.Secure.getString(cr, "enabled_accessibility_services") ?: ""
        val merged = if (cur.contains("com.e7.shop/")) cur
        else if (cur.isBlank()) svc else "$cur:$svc"
        android.provider.Settings.Secure.putString(cr, "enabled_accessibility_services", merged)
        android.provider.Settings.Secure.putInt(cr, "accessibility_enabled", 1)
        "OK"
    } catch (e: Throwable) {
        "Direct: ${e.message}"
    }
}

/** 是否已获得 WRITE_SECURE_SETTINGS（决定是否显示"一键开启"按钮）。 */
private fun hasWriteSecureSettings(context: android.content.Context): Boolean =
    context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

/** 通道二：通过 Shizuku（shell 权限）启用无障碍服务。失败返回错误文本。 */
private fun enableAccessibilityViaShizuku(): String {
    return try {
        val cmd = "settings put secure enabled_accessibility_services com.e7.shop/com.e7.shop.ShopAccessibilityService" +
            " && settings put secure accessibility_enabled 1"
        val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
        val reader = java.io.BufferedReader(java.io.InputStreamReader(p.inputStream))
        val sb = StringBuilder()
        var line: String? = reader.readLine()
        while (line != null) {
            sb.append(line).append('\n')
            line = reader.readLine()
        }
        reader.close()
        p.destroy()
        val out = sb.toString().trim()
        if (out.isEmpty()) "OK" else out
    } catch (e: Throwable) {
        "Shizuku: ${e.message}"
    }
}

@Composable
internal fun ShizukuCard() {
    val context = LocalContext.current
    var available by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    // 通道一是否可用（WRITE_SECURE_SETTINGS 已授权）—— 优先于 Shizuku
    val hasDirect = remember { hasWriteSecureSettings(context) }
    val workingText = stringResource(R.string.shizuku_working)

    // Shizuku 可用性 + 授权结果监听（生命周期内注册/注销）
    DisposableEffect(Unit) {
        val binderListener = Shizuku.OnBinderReceivedListener {
            available = true
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                granted = true
            }
        }
        val permListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addRequestPermissionResultListener(permListener)
        available = Shizuku.pingBinder()
        onDispose {
            Shizuku.removeBinderReceivedListener(binderListener)
            Shizuku.removeRequestPermissionResultListener(permListener)
        }
    }

    if (!available && !hasDirect) return   // 两条通道都不可用 → 该区块完全隐藏（静默降级）

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.shizuku_title), style = MaterialTheme.typography.titleMedium)
            Text(
                if (hasDirect) stringResource(R.string.acc_direct_desc)
                else stringResource(R.string.shizuku_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when {
                // 通道一：WRITE_SECURE_SETTINGS（首选，插拔 USB 不会掉）
                hasDirect -> Button(onClick = {
                    msg = workingText
                    Thread {
                        val r = enableAccessibilityDirect(context)
                        android.os.Handler(android.os.Looper.getMainLooper()).post { msg = r }
                    }.start()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.shizuku_enable_acc))
                }
                // 通道二：Shizuku（未授权先请求权限）
                !granted -> OutlinedButton(onClick = {
                    try {
                        Shizuku.requestPermission(SHIZUKU_REQ)
                    } catch (e: Throwable) {
                        msg = e.message ?: ""
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.shizuku_grant_btn))
                }
                else -> Button(onClick = {
                    msg = workingText
                    Thread {
                        val r = enableAccessibilityViaShizuku()
                        android.os.Handler(android.os.Looper.getMainLooper()).post { msg = r }
                    }.start()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.shizuku_enable_acc))
                }
            }
            if (msg.isNotEmpty()) {
                Text(msg, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/* ============ 工具 ============ */

private fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

internal fun decodeFileSafe(f: java.io.File): Bitmap? =
    try { BitmapFactory.decodeFile(f.absolutePath) } catch (e: Exception) { null }

internal fun loadLogoBitmap(ctx: Context, cfg: AppConfig): Bitmap? {
    return try {
        if (cfg.logoMode == "custom" && cfg.customLogoPath.isNotEmpty()) {
            if (cfg.customLogoPath.startsWith("content://")) {
                ctx.contentResolver.openInputStream(Uri.parse(cfg.customLogoPath))?.use { ins ->
                    BitmapFactory.decodeStream(ins)
                }
            } else BitmapFactory.decodeFile(cfg.customLogoPath)
        } else {
            ctx.assets.open("logo_official.png").use { ins -> BitmapFactory.decodeStream(ins) }
        }
    } catch (e: Exception) { null }
}

internal fun loadBgBitmap(ctx: Context, cfg: AppConfig): Bitmap? {
    val src = cfg.bgImage
    if (src.isEmpty()) return null
    return try {
        if (src.startsWith("content://")) {
            ctx.contentResolver.openInputStream(Uri.parse(src))?.use { ins ->
                BitmapFactory.decodeStream(ins)
            }
        } else BitmapFactory.decodeFile(src)
    } catch (e: Exception) { null }
}
