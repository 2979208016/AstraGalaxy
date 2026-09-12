# 星河 XingHe - R8 rules
-keep class io.github.proify.lyricon.xinghe.xposed.** { *; }
-keep class io.github.libxposed.api.** { *; }

# lyricon provider library: keep entry points used reflectively
-keep class io.github.proify.lyricon.provider.** { *; }
-keep class io.github.proify.lyricon.lyric.model.** { *; }
-dontwarn io.github.proify.**
-dontwarn org.jetbrains.annotations.**
