# LSPosed 通过 META-INF/xposed/java_init.list 里的类名反射加载入口，必须保留原名与成员。
-keep class io.github.proify.astrafirmament.xposed.** { *; }

# libxposed API 是 compileOnly，运行时由框架提供：保留实现类与它要回调的方法签名。
-keep,allowobfuscation class * implements io.github.libxposed.api.XposedModule { *; }
-keepclassmembers class * implements io.github.libxposed.api.XposedModule { public protected *; }
-dontwarn io.github.libxposed.**

# 反射/按名字查找的框架类（不是我们的代码，只是避免 R8 报缺类警告）。
-dontwarn android.app.ActivityThread
-dontwarn android.os.ServiceManager

# 序列化模型（kotlinx.serialization 通过生成的 serializer 访问字段）。
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers @kotlinx.serialization.Serializable class * { *; }
