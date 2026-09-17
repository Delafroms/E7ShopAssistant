# 主题重写：Material 3 配色 + 层次 + 《冰与火之舞》非线性动画

> 起因：用户反馈默认主题（Steam Material）"颜色不好看、所有按钮和界面架构都感觉与 material 不像"。
> 范围：Steam 暗色 / Steam OLED 两套主题 + 页面切换动画（Blue Archive 主题本次未动）。

---

## 一、诊断：为什么"不像 Material"

改之前先查了代码，三条根因（都不是"颜色没调好"，而是**结构性缺失**）：

| 问题 | 改前现状 | Material 3 的做法 |
|---|---|---|
| **用描边表达层次** | `StPanel` / `StTile` 都是 `surface 底色 + 1dp BorderStroke` | 用 **tonal elevation**（`surfaceContainerLow → Highest` 五级色阶），不画边框 |
| **colorScheme 角色残缺** | 只填了 primary/secondary/background/surface/surfaceVariant/outline/error 七个 | 缺 **tertiary、surfaceContainer×5、inverseSurface、outlineVariant、errorContainer、scrim** —— 所以根本做不出层次 |
| **形状不成系统** | 组件里各处硬编码 6dp / 8dp / 22dp | `MaterialTheme.shapes` 统一（4/8/12/16/28dp） |

颜色"不好看"也是同一根源：`#66C0F4`（高饱和亮蓝）+ `#A4D007`（荧光绿）压在纯深底上，
**对比过强且没有中间层次**——正是缺 surfaceContainer 色阶的直接后果。

**一个额外发现**：按钮其实**已经是 M3 原生组件**（`OutlinedButton` / `Button` / `TextButton`），
没有自定义描边按钮。所以"按钮不像 Material"的观感来自配色与层次，不是组件选择。

---

## 二、改动清单

### 1. 配色地基（`Themes.kt`）

补齐 M3 全套角色，Steam 深蓝调保留：

| 角色 | 旧值 | 新值 | 理由 |
|---|---|---|---|
| primary | `#66C0F4` | `#8ECDF5` | 降到 tone 80，不再刺眼 |
| primaryContainer | `#1B2838` | `#004C6E` | 容器色要有辨识度 |
| tertiary | 无 | `#C3D98A` | 降饱和绿，承担大面积用色 |
| surface 系列 | 只有 surface + surfaceVariant | 补 5 级色阶 `#0A0E13 → #26313D` | **层次的关键** |
| error | `#E85C4A` | `#FFB4AB` | M3 暗色标准 |
| inverseSurface / outlineVariant / scrim | 无 | 已补 | 对话框、Snackbar 需要 |

**刻意保留**：`SGreen #A4D007` 仍是鲜艳的——它用于"购买中"状态与 PLAY 键，需要醒目；
大面积铺开的绿交给降饱和的 `tertiary`。这些常量名保留（组件直接引用），只改取值。

### 2. 形状系统

新增 `E7Shapes`（extraSmall 4 / small 8 / medium 12 / large 16 / extraLarge 28），
并在两个主题的 `ColorSchemeProvider` 里挂到 `MaterialTheme.shapes`。

### 3. 组件 M3 化

| 组件 | 改前 | 改后 |
|---|---|---|
| `StPanel` | `surface` + 1dp 描边 + 8dp 硬编码圆角 | `surfaceContainerLow` + `shapes.medium`，**无边框** |
| `StTile` | `surfaceVariant` + 描边 + 6dp | `surfaceContainerHigh` + `shapes.small`，**无边框** |
| `StEngineRow` | 蓝色描边 + 深蓝底表达选中 | `secondaryContainer` + `onSecondaryContainer`（M3 标准选中语义） |
| `StAppearanceRow` / `StNumField` / `StTextField` | 硬编码圆角 | `shapes.small` |

### 4. 《冰与火之舞》风格非线性动画（新增 `ui/DfibEasing.kt`）

这款节奏游戏的动画语言是**打击感**——快速过冲、回弹、节拍顿挫，
与 Material 自带那种"稳重克制"的曲线完全不同。提供 6 个函数：

| 函数 | 特性 | 建议用途 |
|---|---|---|
| `DfibImpact` | 过冲回弹（bezier 0.34, 1.56, 0.64, 1） | 卡片/列表项入场，"啪"的打击感 |
| `DfibSnap` | 急停（easeOutQuint 手感） | 退场、快速切换 |
| `DfibBeat` | **快—顿—收**两段式 | 页面切换入场（节拍感） |
| `DfibElastic` | 衰减振荡 | 强调性弹入（徽章/提示） |
| `DfibBounce` | 落地两次小跳 | 下落/归位、数值跳动 |
| `DfibAnticipate` | 先回撤再冲出 | 退场/收起（蓄力感） |

**已接入**：`MainActivity` 的 `NavHost` 页面切换 —— 入场 `DfibBeat`、退场 `DfibSnap`、
左右方向随导航方向（push/pop）反转。

---

## 三、审查结果：对比度合规（WCAG）

改完以审查员身份跑了对比度检查，**11 项全部 PASS**：

| 配色对 | 对比度 | 要求 |
|---|---|---|
| 正文 / 背景 | 14.08 | ≥4.5 |
| 正文 / 卡片 | 14.08 | ≥4.5 |
| 正文 / 瓦片 | 11.91 | ≥4.5 |
| 次要文本 / 背景 | 10.56 | ≥4.5 |
| 按钮文字 / 主色 | 7.63 | ≥4.5 |
| 选中行文字 / 选中容器 | 7.34 | ≥4.5 |
| 强调色 / 背景 | 10.36 | ≥3 |
| 第三色 / 背景 | 11.56 | ≥3 |
| 错误色 / 背景 | 10.51 | ≥3 |
| 状态绿 / 背景 | 9.86 | ≥3 |
| 状态金 / 背景 | 10.72 | ≥3 |

---

## 四、回滚

改动前已备份原文件：

```
E7ShopAssistant\_backup_Themes_before_m3\Themes_<时间戳>.kt
```

回滚：把备份复制回 `app/src/main/java/com/e7/shop/ui/Themes.kt`，
并删除新增的 `ui/DfibEasing.kt`、撤销 `MainActivity.kt` 的 NavHost 动画参数即可。

---

## 五、尚未完成（等反馈后再定）

1. **页面级入场动画**：目前只有页面切换动画；卡片/列表项**依次错峰入场**（stagger）还没做，
   因为那需要给 `StPanel` 加索引参数、改动所有调用点，属于较大重构。
2. **其余 4 个缓动函数尚未使用**：`DfibImpact` / `DfibElastic` / `DfibBounce` / `DfibAnticipate`
   已定义但还没接到具体元素上——等确认整体风格方向后再铺开。
3. **Blue Archive 主题未动**：用户只提了 Steam Material；BA 有自己完整的设计语言。
4. **实机验证**：改动已编译通过，但设备当时已断开，**视觉效果尚未在真机确认**。
