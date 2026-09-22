package com.e7.shop.ui

import androidx.compose.runtime.Composable
import com.e7.shop.R
import com.e7.shop.ShopAccessibilityService
import com.e7.shop.data.AppConfig

/**
 * V1 主题系统："换房子"架构。
 *
 * 一个主题 = 一整套 UI 实现（色彩 + 排版 + 四页布局结构 + 组件 + 装饰 + 动效），
 * 不只是配色。业务状态 / 回调 / 数据 / 设置 Schema 全部共享（核心逻辑与主题无关），
 * 主题只负责 Presentation —— 切换主题 ≈ 进入另一套完整设计的应用。
 *
 * Steam · 深色卡片 = Steam 客户端控制台语言（深蓝头图 + 品牌蓝 + 绿色 PLAY + 大写分区标题）
 * Steam · OLED 纯黑 = Steam 结构的纯黑变体（平面色块、无渐变，AMOLED 友好）
 * Blue Archive = 明亮学园（网点半调 / 横幅 / 相框卡片 / 仪表盘 / 底部操作条 / 小美少女装饰）
 */
interface E7Theme {
    val id: String
    val labelRes: Int
    @Composable fun HomeScreen(cfg: AppConfig, bot: ShopAccessibilityService.BotState, connected: Boolean)
    @Composable fun RecordsScreen(cfg: AppConfig)
    @Composable fun ProfileScreen(cfg: AppConfig)
    @Composable fun SettingsScreen(cfg: AppConfig, onChangeAppearance: (String) -> Unit)
    @Composable fun ColorSchemeProvider(content: @Composable () -> Unit)
}

object ThemeRegistry {
    val all: List<E7Theme> = listOf(SteamTheme, SteamOledTheme, BlueArchiveTheme, DeepSeekTheme)

    fun forId(id: String): E7Theme = when (id) {
        "oled" -> SteamOledTheme
        "ba", "bluearchive" -> BlueArchiveTheme
        "deepseek" -> DeepSeekTheme
        else -> SteamTheme
    }
}

