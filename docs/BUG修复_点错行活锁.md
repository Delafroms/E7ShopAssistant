# BUG 修复：点错行活锁（奖牌在第 2 栏却点第 1 栏）

> 来源：玩家实机挂机时发现并报告。
> 现象：商店刷出**第 2 物品栏**的神秘奖牌，机器人却点**第 1 物品栏**的购买键；
> 弹窗验证不通过、点了取消，然后**继续点第 1 栏**，反复循环，始终不点第 2 栏。

---

## 根因：行容差比行距还大

`YoloEngine` 给每个候选的行配对容差（`Candidate.tol`）取的是 **图标框高度 × 2**：

```kotlin
// 旧代码
raw.add(Candidate(kind, b.cx, b.cy, b.prob, b.h * 2f, ...))
```

E7 的奖牌图标框高约 150px → **tol ≈ 300px**，而商店**行距只有约 260px**（屏高 20%）。
**容差比行距还大**，于是相邻行之间可以互相"看到"对方的文本与图标。

这一个数字污染了三处：

| 位置 | 后果 |
|---|---|
| `rowBuyPoint` | 目标行自己的"购买"文本若没被 OCR 读到（刚刷出的商品按钮还在渐显），会退而点到**相邻行**的购买键 ← **本次 BUG** |
| `rowSoldOut` | 相邻行的"售罄"文本会让本行被误判为售罄 |
| `rowConfirmed` | 相邻行的图标能把本行"确认"掉 |

**为什么弹窗验证没拦住误买**：`dialogConfirmed` 的三重验证（图标 + 文字 + 精确价格）
确实拦住了 —— 所以玩家看到的是"点了取消、没误买"。安全机制生效，但**定位错误没被发现**，
于是"点错 → 取消 → 再点错"形成活锁。

---

## 修复（4 处）

### 1. 行容差改由**行距**推导（`rowPitch` / `rowTol`）

```kotlin
internal fun rowPitch(lines, imgH): Float {
    val gaps = 购买文本的 y 间距.filter { it > imgH * 0.08f }   // 过滤 OCR 拆段的噪声
    val raw = if (gaps.isNotEmpty()) gaps.min() else imgH * 0.20f // 取最小值，不取中位数
    return raw.coerceIn(imgH * 0.10f, imgH * 0.28f)               // 夹取行距本身
}
internal fun rowTol(lines, imgH) = rowPitch(lines, imgH) * 0.45f   // 恒 < 行距/2
```

两个刻意的选择：
- **取最小值而不是中位数**：OCR 漏读一行时中位数会翻倍（260 → 520），容差反而变得更大；
  最小值仍是真实行距。
- **夹取的是行距、不是容差**：这样 `tol = pitch × 0.45` 恒小于 `pitch / 2`，
  **数学上**保证任何一行都不会与相邻行共用容差区间。

### 2. `rowBuyPoint` 改成"取全局最近 + 检查容差"

旧版先按 tol 过滤再取最近：tol 过大时相邻行的键也在候选里。新版取全局最近的一个，
再检查它是否落在本行容差内 —— **够不着就不点**（fail-closed），绝不跨行。

### 3. `rowConfirmed` 只用候选自带的容差

旧版是 `max(b.h * 2f, c.tol)`，其中 `b.h * 2 ≈ 300px` 同样超过行距，会让相邻行的图标确认本行。
现在直接用 `c.tol`。

### 4. 点错行**立即止损**（消灭活锁）

弹窗出现了但三重验证没过时，**区分两种情况**：

```kotlin
val dlgKind = dialogItemKind(dlg.second.lines)   // 弹窗里的商品名类型
if (dlgKind != null && dlgKind != t.kind) {
    // 弹窗里的商品名明确是别的东西 = 点错了行（定位错误，重试无意义）
    host.handledAdd(t.rowY)          // 立即放弃该行
    host.setError(host.str(R.string.err_row_wrong_target, t.kind, t.rowY))
    return INERT
}
// 商品名读不到 / 与目标一致（只是价格或图标没通过）= 暂时性失败 → 走重试预算
return FAIL
```

**为什么必须加"商品名明确不符"这个前提**：如果只凭"验证没过"就一律放弃该行，
会把"价格 OCR 偶尔抖动"误判成点错行 —— 那就从"点错行"变成了**漏买**，
比原 BUG 更糟。两个引擎（`BotEngine` / `AiBotEngine`）都做了同样处理。

---

## 验证：没有破坏原有功能

| 指标 | 修复前 | 修复后 |
|---|---|---|
| yolo 图标召回 | 100%（14/14） | **100%**（14/14） |
| 误检 | 3 | 3 |
| 均值耗时 | 684ms | 632ms |
| 逐张候选 | — | **与修复前逐字一致** |

逐张候选完全一致，说明本次改动**只影响"候选 → 点击点"的映射**，
不触碰 YOLO 推理与 OCR —— 识别层零影响。

同时审查并确认**无问题**的相邻路径：
- JNI 的 letterbox 坐标还原（`yolov8_det.cpp`）：标准还原，正确
- `slot6Check` / `revealSlot6`：有尝试次数上限 + 滚动验证 + fail-closed，无活锁
- `handledContains` 容差（屏短边 7% ≈ 89px）：小于行距一半，不会串行

---

## 挂机时怎么观察

修复后若再遇到定位问题，日志会明确区分：

- `E7SA.Row WRONG TARGET kind=medal rowY=505 dialogKind=bookmark` → 确认是点错行，已放弃该行
- `E7SA.Row dialog verify FAIL kind=medal rowY=505 dialogKind=?` → 暂时性失败，走重试预算

---

## 遗留边界（诚实记录）

`rowPitch` 的夹取下限是屏高的 10%（1272 屏 → 127px）。如果某种布局的行距**小于屏高 10%**，
夹取会把行距抬到 127px，此时容差 57px 相对真实行距可能超过一半 —— 理论上仍有跨行风险。

实际不会触发：E7 商店行距固定约为屏高 20%（1272 屏 → 254px）。
若将来支持其他布局，应把夹取下限也改为与实测行距联动，而不是固定屏高比例。
