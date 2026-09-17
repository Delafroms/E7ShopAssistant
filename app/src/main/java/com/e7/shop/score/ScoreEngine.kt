package com.e7.shop.score

/**
 * Epic Seven equipment scoring engine (standard community rules).
 *
 * Stat weights (points per unit):
 *   speed          x2.0   (the most valuable stat)
 *   crit rate %    x1.5
 *   crit damage %  x1.1
 *   atk/hp/def %   x1.0
 *   effectiveness % x1.0
 *   effect resist % x1.0
 *   flat atk/def   x0.3
 *   flat hp        x0.15
 *
 * Score = sum(value * weight) per stat line. Percent values are scored in
 * % units (7% crit rate = 7 x 1.5 = 10.5 points), flat values in points.
 */
object ScoreEngine {

    data class StatLine(
        val name: String,
        val value: Double,
        val isPercent: Boolean,
        val points: Double
    )

    data class ScoreResult(
        val lines: List<StatLine>,
        val total: Double
    )

    private enum class Family { SPEED, CRIT_RATE, CRIT_DMG, ATK, HP, DEF, EFF, RES }

    private val familyMatchers: List<Pair<Family, List<String>>> = listOf(
        Family.CRIT_DMG to listOf("暴击伤害", "暴擊傷害", "爆伤", "critical hit damage", "crit damage", "crit dmg"),
        Family.CRIT_RATE to listOf("暴击率", "暴擊率", "暴击几率", "暴击", "暴擊", "critical hit chance", "crit rate", "crit chance"),
        Family.SPEED to listOf("速度", "speed"),
        Family.EFF to listOf("效果命中", "效果命", "命中", "effectiveness"),
        Family.RES to listOf("效果抗性", "效果抵抗", "抗性", "effect resistance"),
        Family.ATK to listOf("攻击力", "攻擊力", "攻击", "attack"),
        Family.HP to listOf("生命力", "生命值", "生命", "health", "hp"),
        Family.DEF to listOf("防御力", "防禦力", "防御", "defense", "def")
    )

    private fun weightOf(family: Family, isPercent: Boolean): Double = when (family) {
        Family.SPEED -> 2.0
        Family.CRIT_RATE -> 1.5
        Family.CRIT_DMG -> 1.1
        Family.EFF, Family.RES -> 1.0
        Family.ATK -> if (isPercent) 1.0 else 0.3
        Family.HP -> if (isPercent) 1.0 else 0.15
        Family.DEF -> if (isPercent) 1.0 else 0.3
    }

    private fun displayName(family: Family, isPercent: Boolean): String = when (family) {
        Family.SPEED -> "Speed"
        Family.CRIT_RATE -> "Crit Rate"
        Family.CRIT_DMG -> "Crit Damage"
        Family.EFF -> "Effectiveness"
        Family.RES -> "Effect Resist"
        Family.ATK -> if (isPercent) "Attack %" else "Attack (flat)"
        Family.HP -> if (isPercent) "HP %" else "HP (flat)"
        Family.DEF -> if (isPercent) "Defense %" else "Defense (flat)"
    }

    /** Parse one OCR text line into a stat line, or null when unrecognized. */
    fun parseLine(text: String): StatLine? {
        var line = text.trim()
        if (line.isEmpty()) return null
        val lower = line.lowercase()

        var family: Family? = null
        var nameUsed: String? = null
        for ((f, names) in familyMatchers) {
            for (n in names) {
                if (lower.contains(n.lowercase())) {
                    family = f
                    nameUsed = n
                    break
                }
            }
            if (family != null) break
        }
        if (family == null) return null

        val nameIdx = lower.indexOf(nameUsed!!.lowercase())
        val tail = line.substring(nameIdx + nameUsed.length)

        // numeric value right after the stat name (with optional % sign)
        val m = Regex("([0-9]+(?:\\.[0-9]+)?)").find(tail) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val hasPercent = tail.contains("%") || tail.contains("％")

        // percent families are always percent; atk/hp/def switch by the symbol
        val isPercent = when (family) {
            Family.SPEED -> false
            Family.CRIT_RATE, Family.CRIT_DMG, Family.EFF, Family.RES -> true
            else -> hasPercent
        }
        val weight = weightOf(family, isPercent)
        val points = Math.round(value * weight * 10.0) / 10.0

        return StatLine(
            name = displayName(family, isPercent),
            value = value,
            isPercent = isPercent,
            points = points
        )
    }

    /** Score a list of OCR lines (one gear stat per line). */
    fun score(lines: List<String>): ScoreResult {
        val parsed = ArrayList<StatLine>()
        val seen = HashSet<String>()
        for (l in lines) {
            val s = parseLine(l) ?: continue
            if (!seen.add(s.name)) continue
            parsed.add(s)
        }
        parsed.sortByDescending { it.points }
        val total = Math.round(parsed.sumOf { it.points } * 10.0) / 10.0
        return ScoreResult(parsed, total)
    }
}
