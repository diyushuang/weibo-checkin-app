# Phase 0：release 先单独开启 R8 / 资源收缩，验证现有代码与依赖的 keep 规则。
# 当前依赖（OkHttp / WorkManager / AndroidX）均自带 consumer rules；
# 项目无反射、无 getIdentifier、无动态资源查找。
# 后续引入 Hilt / Compose 时，它们的 consumer rules 会自动合并；
# 如遇 release-only 崩溃，把最小 keep 规则补在这里并记录原因。
