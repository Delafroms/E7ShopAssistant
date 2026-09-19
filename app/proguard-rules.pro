# ---- Android components declared in the manifest (R8 must keep their names) ----
-keep class com.e7.shop.ShopAccessibilityService { *; }
-keep class com.e7.shop.SplashActivity { *; }
-keep class com.e7.shop.MainActivity { *; }
-keep class com.e7.shop.score.EquipmentScoreActivity { *; }

# ---- Native OCR/YOLO bridge: JNI symbols are Java_com_e7_shop_bot_PpOcr_*
#      and Java_com_e7_shop_bot_YoloDet_*, so these two class names must survive R8.
-keep class com.e7.shop.bot.PpOcr { *; }
-keep class com.e7.shop.bot.YoloDet { *; }

# ---- Shizuku provider：清单里声明了，但类名来自第三方库。
#      R8 曾把它当无用代码删掉（usage.txt 有记录）→ Shizuku 通道静默失效 ----
-keep class rikka.shizuku.ShizukuProvider { *; }
-keep class rikka.shizuku.Shizuku { *; }

# ---- OkHttp / coroutines used by the WebDAV sync ----
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
