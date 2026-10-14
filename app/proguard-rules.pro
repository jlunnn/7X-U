# R8 keep 規則（車機 app）
# 為什麼要 keep 自己成個 package：車控／音頻部分靠系統按名載入組件、反射讀 OEM 類別，
# R8 改名／移除會靜靜壞掉。androidx/material 照縮（體積主要來自佢哋）。

-keep class com.sevenxu.eq.** { *; }

-keep class * extends android.app.Activity
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.content.ContentProvider

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

-dontwarn **
-dontnote **

-allowaccessmodification
-optimizationpasses 5
-repackageclasses
