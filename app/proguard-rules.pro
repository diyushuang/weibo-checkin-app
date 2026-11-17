# Phase 0：release 先单独开启 R8 / 资源收缩，验证现有代码与依赖的 keep 规则。
# 当前依赖（OkHttp / WorkManager / AndroidX / Hilt / Compose）均自带 consumer rules；
# 项目无反射、无 getIdentifier、无动态资源查找。
# Hilt 与 Compose 落地后已实跑 release 构建（R8 + 资源收缩），未再额外补 keep；
# 如遇 release-only 崩溃，把最小 keep 规则补在这里并记录原因。

# WorkManager 把 worker 的**类名**以字符串存进 WorkSpec 数据库（work.db），
# 任务恢复时按类名反射实例化。这条记录会跨版本存活：用户从旧版（未开 R8）升级上来的
# 那一刻，库里那条 `wcm-keepalive` 存的是 `com.wcm.checkin.CheckInWorker` 的全名。
#
# `work-runtime:2.10.0` 自带的 consumer rule 是 `-keepnames class * extends
# androidx.work.ListenableWorker`，已经能保住类名；这里显式再钉一次，是为了：
#   1) 不依赖三方库的规则细节（换版本后若改成 allowobfuscation，这里仍兜底）；
#   2) 把「重命名的后果」写在能读到的地方，而不是等线上出现 ClassNotFoundException。
-keep class com.wcm.checkin.CheckInWorker { <init>(...); }

# 同 CheckInWorker：WorkManager 按类名反射实例化，类名进 work.db 后跨版本存活。
-keep class com.wcm.checkin.EngageWorker { <init>(...); }
