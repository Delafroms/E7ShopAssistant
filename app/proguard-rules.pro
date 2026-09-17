# ---- Android components declared in the manifest (R8 must keep their names) ----
-keep class com.e7.shop.ShopAccessibilityService { *; }
-keep class com.e7.shop.SplashActivity { *; }
-keep class com.e7.shop.MainActivity { *; }
-keep class com.e7.shop.score.EquipmentScoreActivity { *; }

# ---- Native OCR/YOLO bridge: JNI symbols are Java_com_e7_shop_bot_PpOcr_*
#      and Java_com_e7_shop_bot_YoloDet_*, so these two class names must survive R8.
-keep class com.e7.shop.bot.PpOcr { *; }
-keep class com.e7.shop.bot.YoloDet { *; }

# ---- OkHttp / coroutines used by the WebDAV sync ----
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
