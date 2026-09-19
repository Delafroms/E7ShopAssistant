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
# 2026-09-19：原先的 `-keep class okhttp3.** { *; }` 让 R8 对整库失效 —— 无法裁剪，
# 也让人分辨不出"哪些类真的需要保活"。OkHttp 在本项目里是**直接调用**（无反射、无
# 序列化框架），其 AAR 自带 consumer 规则；这里只保活 App 直接引用的类型。
-keep class okhttp3.OkHttpClient { *; }
-keep class okhttp3.Request { *; }
-keep class okhttp3.Response { *; }
-keep class okhttp3.ResponseBody { *; }
-keep class okhttp3.RequestBody { *; }
-keep class okhttp3.MediaType { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
