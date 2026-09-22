package com.e7.shop

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.e7.shop.data.AppConfig
import com.e7.shop.ui.DsMaid
import com.e7.shop.ui.ThemeRegistry

/**
 * 进入动画（2026-09-20 **主题化重做**）。
 *
 * 旧版是一段固定的「heyyo x E7SA」文字 fade+scale —— 用户说它"早已经是更老的版本了"，
 * 而且**三个主题长得一模一样**，换了房子却没有换感觉。
 *
 * 现在：**谁来迎接你，取决于你上次离开时住的是哪个房子**
 * （由 `AppConfig.lastTheme` 记录，[AppConfig.appearance] 的 setter 同步写入）：
 *  · Steam        → 品牌字缩放淡入（保留旧版的味道，但改用当前主题配色）
 *  · Blue Archive → 光环自中心扩散
 *  · DeepSeek     → **女仆从下方迎上来** + 「欢迎回来」
 *
 * 交互与旧版一致：点任意处跳过、2 秒后自动进入主界面、设置里可整体关闭（showSplash）。
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cfg = AppConfig(this)
        if (!cfg.showSplash) {
            goMain()
            return
        }

        // "谁来迎接你" = 上次离开时的主题；从未记录过则回落到当前主题
        val themeId = cfg.lastTheme.ifEmpty { cfg.appearance }

        setContent {
            ThemeRegistry.forId(themeId).ColorSchemeProvider {
                SplashScene(themeId) { goMain() }
            }
        }

        // 自动进入主界面（与旧版一致的时间感）
        Handler(Looper.getMainLooper()).postDelayed({ goMain() }, 2000)
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

/** 动画总时长（ms）：统一推进到 1，各主题按自己的节奏取用这条进度。 */
private const val INTRO_MS = 950

@Composable
private fun SplashScene(themeId: String, onDone: () -> Unit) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        anim.animateTo(1f, tween(INTRO_MS, easing = FastOutSlowInEasing))
    }
    val t = anim.value

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDone() },
        color = MaterialTheme.colorScheme.background
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (themeId) {
                "deepseek" -> DsIntro(t)
                "ba", "bluearchive" -> BaIntro(t)
                else -> SteamIntro(t)
            }
        }
    }
}

/** DeepSeek：女仆从下方迎上来，随后浮出问候语（"女仆等你回家"）。 */
@Composable
private fun DsIntro(t: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        DsMaid(
            modifier = Modifier
                .size(150.dp)
                .offset(y = ((1f - t) * 70).dp)
                .alpha(t.coerceIn(0f, 1f))
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "欢迎回来",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.alpha(((t - 0.45f) / 0.55f).coerceIn(0f, 1f))
        )
    }
}

/** Blue Archive：光环自中心扩散并淡出。 */
@Composable
private fun BaIntro(t: Float) {
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(240.dp)) {
            val r = size.minDimension * (0.16f + 0.34f * t)
            drawCircle(
                color = Color(0xFF7FB2F0).copy(alpha = ((1f - t) * 0.6f).coerceIn(0f, 1f)),
                radius = r,
                center = center,
                style = Stroke(width = size.minDimension * 0.035f)
            )
        }
        Text(
            "E7 商店助手",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.scale(0.8f + 0.2f * t).alpha(t.coerceIn(0f, 1f))
        )
    }
}

/** Steam：品牌字缩放淡入（保留旧版的味道）。 */
@Composable
private fun SteamIntro(t: Float) {
    Text(
        "heyyo x E7SA",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.scale(0.7f + 0.3f * t).alpha(t.coerceIn(0f, 1f))
    )
}
