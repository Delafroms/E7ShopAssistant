package com.e7.shop

/**
 * Tiny Markdown -> HTML renderer for the in-app changelog. No external
 * dependency (keeps the APK lean): supports the subset used in the changelog -
 * headings, bullet/numbered lists, bold, inline code, code fences, block
 * quotes, and horizontal rules, plus <br> for newlines. Everything else is
 * escaped so the page never executes JavaScript.
 *
 * V1 reading experience:
 *  - theme-aware (dark / light + accent), so the changelog always matches
 *    the active theme instead of forcing the old dark-only palette;
 *  - larger type, looser line height, real paragraph/heading/list spacing;
 *  - consecutive list items are grouped into a single <ul>/<ol> (the old
 *    renderer wrapped every bullet in its own list, which produced gaps);
 *  - content is centered with a comfortable max width on wide screens.
 */
object MarkdownRenderer {

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    /** Inline emphasis: **bold** and `code`. */
    private fun inline(s: String): String {
        var t = esc(s)
        t = t.replace(Regex("`([^`]+)`"), "<code>$1</code>")
        t = t.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
        t = t.replace(Regex("\\*([^*]+)\\*"), "<i>$1</i>")
        return t
    }

    /** @param bg  page background   @param fg  body text   @param accent  headings/accent
     *  @param soft secondary text   @param card code/quote backgrounds */
    private fun css(bg: String, fg: String, accent: String, soft: String, card: String): String =
        """
        body{background:$bg;color:$fg;font-family:-apple-system,Roboto,'Noto Sans','Noto Sans SC',sans-serif;
             font-size:15.5px;line-height:1.78;margin:0;}
        .wrap{max-width:760px;margin:0 auto;padding:22px 22px 56px;}
        h1{color:$accent;font-size:23px;line-height:1.3;margin:4px 0 14px;font-weight:700;
           border-bottom:2px solid $accent;padding-bottom:12px;}
        h2{color:$accent;font-size:18.5px;line-height:1.35;margin:30px 0 12px;font-weight:700;
           border-left:4px solid $accent;padding-left:10px;}
        h3{color:$fg;font-size:16px;margin:22px 0 8px;font-weight:700;}
        p{margin:10px 0;}
        ul,ol{margin:8px 0 10px;padding-left:22px;}
        li{margin:7px 0;line-height:1.7;}
        code{background:$card;color:$accent;padding:1.5px 6px;border-radius:5px;
             font-family:monospace;font-size:13.5px;}
        pre{background:$card;padding:12px 14px;border-radius:8px;overflow:auto;margin:10px 0;}
        pre code{background:none;padding:0;}
        blockquote{border-left:3px solid $accent;margin:12px 0;padding:8px 16px;
                   color:$soft;background:$card;border-radius:0 8px 8px 0;}
        hr{border:none;border-top:1px solid $soft;opacity:.35;margin:24px 0;}
        b{color:$accent;font-weight:700;}
        .tag{color:$accent;}
        """.trimIndent()

    fun render(md: String, dark: Boolean, accent: String, bg: String): String {
        val fg = if (dark) "#E8ECF5" else "#24303E"
        val soft = if (dark) "#A9B8D8" else "#5A6B80"
        val card = if (dark) "#1A2440" else "#E3EDF8"
        val page = if (dark) bg else bg

        val sb = StringBuilder()
        sb.append("""
            <!DOCTYPE html><html><head><meta charset=utf-8>
            <meta name=viewport content="width=device-width,initial-scale=1">
            <style>${css(page, fg, accent, soft, card)}</style></head><body><div class=wrap>
        """.trimIndent())

        val lines = md.split("\n")
        var inCode = false
        var inUl = false
        var inOl = false

        fun closeLists() {
            if (inUl) { sb.append("</ul>"); inUl = false }
            if (inOl) { sb.append("</ol>"); inOl = false }
        }

        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()
            when {
                line.startsWith("```") -> {
                    closeLists()
                    if (inCode) { sb.append("</code></pre>"); inCode = false }
                    else { sb.append("<pre><code>"); inCode = true }
                }
                inCode -> sb.append(esc(raw)).append("\n")
                line.startsWith("# ") -> {
                    closeLists(); sb.append("<h1>").append(inline(line.substring(2))).append("</h1>")
                }
                line.startsWith("## ") -> {
                    closeLists(); sb.append("<h2>").append(inline(line.substring(3))).append("</h2>")
                }
                line.startsWith("### ") -> {
                    closeLists(); sb.append("<h3>").append(inline(line.substring(4))).append("</h3>")
                }
                line.startsWith("> ") -> {
                    closeLists(); sb.append("<blockquote>").append(inline(line.substring(2))).append("</blockquote>")
                }
                line == "---" || line == "***" -> {
                    closeLists(); sb.append("<hr>")
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    if (!inUl) {
                        closeLists(); sb.append("<ul>"); inUl = true
                    }
                    sb.append("<li>").append(inline(line.substring(2))).append("</li>")
                }
                Regex("^\\d+\\. ").containsMatchIn(line) -> {
                    if (!inOl) {
                        closeLists(); sb.append("<ol>"); inOl = true
                    }
                    sb.append("<li>").append(inline(line.replace(Regex("^\\d+\\. "), ""))).append("</li>")
                }
                line.isNotEmpty() -> {
                    closeLists(); sb.append("<p>").append(inline(line)).append("</p>")
                }
                else -> sb.append("<br>")
            }
            i++
        }
        closeLists()
        if (inCode) sb.append("</code></pre>")
        sb.append("</div></body></html>")
        return sb.toString()
    }

    /** 旧版兼容：暗色默认（重构完成前的历史调用点）。 */
    fun render(md: String): String = render(md, true, "#D4AF37", "#0B1022")
}
