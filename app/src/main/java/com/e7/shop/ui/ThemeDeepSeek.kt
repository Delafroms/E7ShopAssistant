package com.e7.shop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.e7.shop.R
import com.e7.shop.ShopAccessibilityService
import com.e7.shop.data.AppConfig

/**
 * DeepSeek 风格主题（2026-09-20 新增）。
 *
 * **布局取"竖向卡片流"（方案 A）**，但与 Steam 的卡片语言刻意拉开差异：
 *  · 单列、**更大圆角（24dp）**、卡片间距更松（16dp）
 *  · 主色 = DeepSeek 品牌蓝 #4D6BFE，底色 #0F1420
 *  · 首页顶部是**自绘鲸鱼**（[DsWhale]）作为视觉符号
 *
 * **版权说明（重要）**：DeepSeek 的公开拟人形象「鲸鱼娘」（上善无形的原创 OC「溟月」，
 * 及 ZipZipPipe 基于其二次设计的女仆版）以 **CC BY-NC-SA 4.0** 开放，该协议**禁止商业使用**
 * 且要求相同方式共享 —— 与本项目的 **AGPL-3.0**（允许商用、不允许附加限制）不兼容。
 * 因此本主题**不含任何第三方形象**：鲸鱼是自然生物、女仆是通用服装概念，
 * 图形全部为本项目自绘，不构成对任何具体作品的复制或演绎。
 *
 * 页面复用：记录 / 我的 / 设置三页直接复用 Steam 的实现（`StRecords` 等）——
 * 它们全部走 `MaterialTheme.colorScheme` 取色，所以在本主题下会自动呈现 DeepSeek 配色，
 * 同时**天然继承 SettingsSchema 的全部设置项**（设置页是 Schema 驱动的）。
 */
object DeepSeekTheme : E7Theme {
    override val id = "deepseek"
    override val labelRes = R.string.appearance_deepseek

    @Composable
    override fun ColorSchemeProvider(content: @Composable () -> Unit) =
        MaterialTheme(colorScheme = DsScheme, shapes = DsShapes, content = content)

    @Composable
    override fun HomeScreen(
        cfg: AppConfig,
        bot: ShopAccessibilityService.BotState,
        connected: Boolean
    ) = DsHome(cfg, bot, connected)

    @Composable
    override fun RecordsScreen(cfg: AppConfig) = StRecords(cfg)

    @Composable
    override fun ProfileScreen(cfg: AppConfig) = StProfile(cfg)

    @Composable
    override fun SettingsScreen(cfg: AppConfig, onChangeAppearance: (String) -> Unit) =
        StSettings(cfg, onChangeAppearance)
}

private val DsScheme = darkColorScheme(
    primary = Color(0xFF4D6BFE),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF7C93FF),
    background = Color(0xFF0F1420),
    onBackground = Color(0xFFE6EAF5),
    surface = Color(0xFF1A2030),
    onSurface = Color(0xFFE6EAF5),
    surfaceContainerLow = Color(0xFF161C2A),
    surfaceContainerHigh = Color(0xFF232B3E),
    error = Color(0xFFFF6B6B)
)

/** 比 Steam 更圆、更大间隔的形态语言（"竖向卡片流"观感的一半来自这里）。 */
private val DsShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp)
)

/** DeepSeek 卡片：大圆角 + 低层色，靠留白而不是描边分层。 */
@Composable
private fun DsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(18.dp)) { content() }
    }
}

/**
 * 首页（方案 A：竖向卡片流）。
 *
 * 结构：鲸鱼主视觉卡 → 控制卡 → 统计卡，三张卡竖排。
 * 与 Steam（头图 + 面板 + 网格）和 BA（横幅 + 相框 + 底部操作条）都不同。
 */
@Composable
private fun DsHome(cfg: AppConfig, bot: ShopAccessibilityService.BotState, connected: Boolean) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { DsHero(bot, connected) }
        item { DsControls(bot) }
        item { DsStats(bot) }
    }
}

/** 主视觉卡：自绘鲸鱼 + 当前阶段 + 运行时长。 */
@Composable
private fun DsHero(bot: ShopAccessibilityService.BotState, connected: Boolean) {
    DsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DsMaid(modifier = Modifier.size(76.dp))
            Spacer(Modifier.size(16.dp))
            Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(stageResOf(bot.stage)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (!connected) "未连接无障碍服务" else DsUptime(bot),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
        if (bot.lastError.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                bot.lastError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 控制卡：开始 / 暂停 / 停止，横排大按钮（竖排卡片流里的"操作区"）。 */
@Composable
private fun DsControls(bot: ShopAccessibilityService.BotState) {
    DsCard {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { ShopAccessibilityService.instance?.startBot(0, 0) },
                enabled = !bot.running,
                modifier = Modifier.weight(1f)
            ) { Text("开始") }
            OutlinedButton(
                onClick = {
                    val svc = ShopAccessibilityService.instance ?: return@OutlinedButton
                    if (svc.state.paused) svc.resumeBot() else svc.pauseBot()
                },
                enabled = bot.running,
                modifier = Modifier.weight(1f)
            ) { Text(if (bot.paused) "继续" else "暂停") }
            OutlinedButton(
                onClick = { ShopAccessibilityService.instance?.stopBot() },
                enabled = bot.running,
                modifier = Modifier.weight(1f)
            ) { Text("停止") }
        }
    }
}

/** 统计卡：两列瓦片。 */
@Composable
private fun DsStats(bot: ShopAccessibilityService.BotState) {
    DsCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DsTile("书签", bot.bookmarksGot.toString(), Modifier.weight(1f))
            DsTile("奖牌", bot.medalsGot.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DsTile("刷新", bot.refreshes.toString(), Modifier.weight(1f))
            DsTile("天空石", bot.skystonesSpent.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun DsTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
        }
    }
}

/**
 * 自绘鲸鱼（本主题的视觉符号）。
 *
 * 为什么手画而不用图片：矢量 → 任意分辨率清晰、体积为零、可随主题取色；
 * 也避免了引入任何第三方素材（见本文件顶部的版权说明）。
 */
@Composable
private fun DsWhale(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // 身体
        val body = Path().apply {
            moveTo(w * 0.08f, h * 0.60f)
            cubicTo(w * 0.12f, h * 0.24f, w * 0.66f, h * 0.20f, w * 0.82f, h * 0.46f)
            cubicTo(w * 0.94f, h * 0.66f, w * 0.68f, h * 0.88f, w * 0.42f, h * 0.84f)
            cubicTo(w * 0.22f, h * 0.81f, w * 0.06f, h * 0.76f, w * 0.08f, h * 0.60f)
            close()
        }
        drawPath(body, tint)
        // 尾鳍
        val tail = Path().apply {
            moveTo(w * 0.10f, h * 0.58f)
            lineTo(w * 0.00f, h * 0.30f)
            lineTo(w * 0.24f, h * 0.46f)
            close()
        }
        drawPath(tail, tint.copy(alpha = 0.82f))
        // 眼睛
        drawCircle(Color.White, radius = w * 0.032f, center = Offset(w * 0.64f, h * 0.50f))
    }
}

/**
 * 自绘二次元女仆（2026-09-20 新增）。
 *
 * **画风 = 赛璐璐（Cel）**：大色块 + 硬边缘阴影、无柔和过渡 —— 这正是矢量图形最擅长的
 * 二次元表达（扁平插画的主流做法），所以能同时做到"有那味"和"任何分辨率都清晰、体积为零"。
 * Q 版半身比例（头大身小），在这个尺寸下最容易画得可爱、也最不容易崩。
 *
 * **版权**：通用女仆形象（蓝发蓝瞳 + 女仆发饰 + 围裙），本项目自绘，
 * **不指向任何具体作品或 OC** —— 见本文件顶部的许可说明。
 *
 * 图层顺序（从后到前）：后发 → 身体 → 围裙 → 脸 → 刘海 → 眼睛 → 腮红 → 发饰。
 * 顺序错了会互相盖住，这是矢量人物画最容易翻车的地方。
 */
@Composable
internal fun DsMaid(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val hair = Color(0xFF4D6BFE)
        val hairDark = Color(0xFF2F3FD0)
        val hairLight = Color(0xFF8FA6FF)
        val skin = Color(0xFFFFE3D5)
        val dress = Color(0xFF161C2A)
        val apron = Color(0xFFF2F5FF)
        val eye = Color(0xFF2F6BFF)
        val blush = Color(0x40FF8FA3)

        // ① 后发：整个头部的底，决定发型轮廓
        drawOval(hairDark, topLeft = Offset(w * 0.17f, h * 0.05f), size = Size(w * 0.66f, h * 0.74f))

        // ② 身体：女仆装（深色）
        drawPath(
            Path().apply {
                moveTo(w * 0.28f, h * 1.00f)
                lineTo(w * 0.31f, h * 0.72f)
                cubicTo(w * 0.40f, h * 0.66f, w * 0.60f, h * 0.66f, w * 0.69f, h * 0.72f)
                lineTo(w * 0.72f, h * 1.00f)
                close()
            },
            dress
        )

        // ③ 围裙：白色，压在女仆装之上
        drawPath(
            Path().apply {
                moveTo(w * 0.37f, h * 1.00f)
                lineTo(w * 0.39f, h * 0.77f)
                cubicTo(w * 0.46f, h * 0.72f, w * 0.54f, h * 0.72f, w * 0.61f, h * 0.77f)
                lineTo(w * 0.63f, h * 1.00f)
                close()
            },
            apron
        )

        // ④ 脸
        drawOval(skin, topLeft = Offset(w * 0.26f, h * 0.15f), size = Size(w * 0.48f, h * 0.53f))

        // ⑤ 刘海：三瓣式（中间一瓣 + 两侧），赛璐璐的典型发形
        drawPath(
            Path().apply {
                moveTo(w * 0.21f, h * 0.44f)
                cubicTo(w * 0.19f, h * 0.08f, w * 0.81f, h * 0.08f, w * 0.79f, h * 0.44f)
                cubicTo(w * 0.72f, h * 0.30f, w * 0.64f, h * 0.37f, w * 0.57f, h * 0.27f)
                cubicTo(w * 0.49f, h * 0.40f, w * 0.38f, h * 0.31f, w * 0.29f, h * 0.45f)
                close()
            },
            hair
        )

        // ⑥ 眼睛（大眼 + 白高光，二次元的辨识核心）
        drawOval(eye, topLeft = Offset(w * 0.335f, h * 0.43f), size = Size(w * 0.13f, h * 0.11f))
        drawOval(eye, topLeft = Offset(w * 0.535f, h * 0.43f), size = Size(w * 0.13f, h * 0.11f))
        drawCircle(Color.White, radius = w * 0.022f, center = Offset(w * 0.385f, h * 0.462f))
        drawCircle(Color.White, radius = w * 0.022f, center = Offset(w * 0.585f, h * 0.462f))

        // ⑦ 腮红
        drawOval(blush, topLeft = Offset(w * 0.29f, h * 0.535f), size = Size(w * 0.11f, h * 0.05f))
        drawOval(blush, topLeft = Offset(w * 0.60f, h * 0.535f), size = Size(w * 0.11f, h * 0.05f))

        // ⑧ 女仆发饰：白色蕾丝带 + 侧边小球
        drawOval(apron, topLeft = Offset(w * 0.29f, h * 0.13f), size = Size(w * 0.42f, h * 0.075f))
        drawCircle(hairLight, radius = w * 0.032f, center = Offset(w * 0.715f, h * 0.175f))
    }
}

/** 运行时长（未开始显示占位）。 */
private fun DsUptime(bot: ShopAccessibilityService.BotState): String {
    if (bot.startedAt <= 0L) return "未开始"
    val min = ((System.currentTimeMillis() - bot.startedAt) / 60000L).coerceAtLeast(0L)
    return "已运行 ${min / 60} 小时 ${min % 60} 分"
}
