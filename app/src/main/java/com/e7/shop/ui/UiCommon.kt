package com.e7.shop.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.e7.shop.R
import com.e7.shop.ShopAccessibilityService

/**
 * 两个主题共用的 UI 片段（2026-09-19 抽取）。
 *
 * 为什么：ThemeSteam 与 ThemeBlueArchive 有约 41% 的逐字重复行（LCS 实测 420/1019），
 * 而「改一条漏一条」是本项目付过代价的系统性风险 —— BA 主题曾漏掉「风险确认」闸门
 * （点开始完全没反应）、漏掉键盘选项。这里把**逻辑与共用弹窗**抽出来，
 * 渲染细节仍留在各自主题里。
 *
 * 验证方式（本机无法看图，所以用对拍）：重构前后用 uiautomator 元素树（文本+坐标）
 * 逐屏比对，要求**零差异**；同时截图像素比对，要求不超过同版本连拍的噪声底线。
 */

/** 阶段 → 文案资源（两主题共用；纯映射，不涉及渲染）。 */
internal fun stageResOf(stage: ShopAccessibilityService.Stage): Int = when (stage) {
    ShopAccessibilityService.Stage.IDLE -> R.string.not_running
    ShopAccessibilityService.Stage.CHECKING -> R.string.checking
    ShopAccessibilityService.Stage.BUYING -> R.string.buying
    ShopAccessibilityService.Stage.REFRESHING -> R.string.refreshing
    ShopAccessibilityService.Stage.RESTING -> R.string.resting
    ShopAccessibilityService.Stage.PAUSED -> R.string.paused
    ShopAccessibilityService.Stage.WAITING -> R.string.waiting_game
}

/**
 * 图片选择器（背景图 / 自定义 Logo 共用）。
 *
 * 抽出来的理由：这段「选图 + 持久化读权限 + 失败只记日志」的逻辑原先在两个主题里
 * 各写两遍（背景一次、Logo 一次，共 4 份）——任何一处修权限处理都容易漏掉其余三处。
 */
@Composable
internal fun rememberImagePicker(
    ctx: Context,
    onPicked: (Uri) -> Unit
): ActivityResultLauncher<Array<String>> =   // OpenDocument 的入参是 MIME 类型数组
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // 持久化读权限失败：本次选择仍生效，只是重启后可能需重新选图
                android.util.Log.w("E7SA.UI", "persist uri permission failed: " + e.message)
            }
            onPicked(uri)
        }
    }

/** 风险确认弹窗（两主题共用：标题、正文、两个按钮完全一致）。 */
@Composable
internal fun RiskConfirmDialog(onDismiss: () -> Unit, onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dlg_risk_title)) },
        text = { Text(stringResource(R.string.dlg_risk_msg)) },
        confirmButton = {
            TextButton(onClick = onAccept) { Text(stringResource(R.string.dlg_accept_risk)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dlg_cancel)) }
        }
    )
}