# DNSGuardian — reglas ProGuard/R8

# Mantener clases de accesibilidad (el sistema las invoca por reflexión)
-keep class es.adeodato.dnsguardian.service.GuardianAccessibilityService { *; }

# Mantener receptores y actividades declarados en el manifest
-keep class es.adeodato.dnsguardian.BootReceiver { *; }
-keep class es.adeodato.dnsguardian.GuardianAdminReceiver { *; }

# Mantener clases de seguridad
-keep class es.adeodato.dnsguardian.security.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Compose no necesita reglas extra con R8 full mode
