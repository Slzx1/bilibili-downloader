# Ktor
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }

# Ktor 引用了 JDK 管理类（Android 上不存在），R8 需要忽略
-dontwarn java.lang.management.**
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ffmpeg-kit
-keep class com.arthenica.ffmpegkit.** { *; }

# kotlinx.serialization：保留 @Serializable 类的 serializer
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 应用 @Serializable 数据类（VideoFormat/ParseResult/TaskStatus/ProgressMessage 等）
-keep,includedescriptorclasses class com.bilidown.app.**$$serializer { *; }
-keepclassmembers class com.bilidown.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.bilidown.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.bilidown.app.** { *; }
