# AdeoShield

**Control parental para Android basado en filtrado DNS-over-HTTPS, con bloqueo de ajustes y protección por PIN.**

AdeoShield es una aplicación de control parental para Android, desarrollada de forma nativa en Kotlin y Jetpack Compose. Filtra el tráfico de red del dispositivo a través de DNS-over-HTTPS (DoH) contra los servidores de AdGuard Family DNS, e impide que el contenido filtrado se desactive fácilmente mediante un sistema de protección por PIN y bloqueo de las pantallas de configuración sensibles del sistema.

El proyecto nace como una herramienta real para proteger a una familia, y como pieza de un ecosistema de control parental por capas en el que AdeoShield actúa como capa de filtrado en los dispositivos Android.

> **Nota de alcance:** AdeoShield es un proyecto personal y educativo. No es un producto comercial ni una solución de seguridad infalible. Como toda app de control parental basada en el dispositivo, está sujeta a las limitaciones de la plataforma Android (ver la sección *Limitaciones conocidas*). Su objetivo es elevar significativamente la dificultad de eludir el filtrado, no garantizar lo imposible.

---

## Características principales

- **Filtrado DNS-over-HTTPS (DoH):** todo el tráfico DNS del dispositivo se canaliza mediante un `VpnService` local y se resuelve de forma cifrada contra AdGuard Family DNS, que bloquea contenido adulto y sitios maliciosos a nivel de resolución de nombres.
- **Protección por PIN:** el acceso a la app está protegido por un PIN derivado con `PBKDF2WithHmacSHA256` (120.000 iteraciones, *salt* aleatorio por instalación). El PIN no se almacena en claro en ningún momento. Bloqueo temporal tras varios intentos fallidos.
- **Bloqueo de ajustes sensibles:** un servicio de Accesibilidad detecta e impide el acceso a las pantallas del sistema que permitirían desactivar la protección (Accesibilidad, VPN, DNS privado, administradores de dispositivo, opciones de desarrollador).
- **Protecciones configurables:** conjunto de protecciones activables de forma granular. Cinco protecciones vienen activas por defecto; el bloqueo de "Ajustes del sistema" se ofrece como opción avanzada desactivada por defecto, por su mayor impacto en la usabilidad.
- **Bloqueo de aplicaciones por PIN:** el adulto responsable selecciona, de la lista de aplicaciones instaladas, cuáles quiere proteger. Cuando alguien intenta abrir una app bloqueada, AdeoShield intercepta la apertura y solicita el PIN antes de permitir el acceso. El control de las apps bloqueadas queda así protegido por la misma autenticación que el resto de la configuración.
- **Detección de DNS privado del sistema:** la app detecta cuando el DNS privado (DoT) del sistema operativo podría interceptar las consultas antes de que entren en el túnel, y avisa de la situación.

---

## Pila tecnológica

| Área | Tecnología |
|------|------------|
| Lenguaje | Kotlin |
| UI | Jetpack Compose |
| Filtrado de red | `VpnService` (túnel local) + DNS-over-HTTPS |
| DNS upstream | AdGuard Family DNS |
| Criptografía del PIN | PBKDF2WithHmacSHA256 (120.000 iteraciones) |
| Bloqueo de ajustes y apps | `AccessibilityService` |

---

## Arquitectura de seguridad

AdeoShield combina varias capas para dificultar la elusión del filtrado:

1. **Capa de resolución.** El `VpnService` captura las consultas DNS del dispositivo y las reenvía cifradas (DoH) a un resolutor con filtrado familiar. El contenido no deseado nunca llega a resolverse.
2. **Capa de persistencia.** El servicio de Accesibilidad vigila las pantallas de ajustes que permitirían apagar la VPN, el servicio de accesibilidad o cambiar el DNS, y las bloquea o las protege con PIN. El mismo servicio intercepta la apertura de las aplicaciones que el adulto haya marcado como bloqueadas y exige el PIN para acceder a ellas.
3. **Capa de autenticación.** El PIN, derivado con PBKDF2, protege la configuración de la propia app frente a cambios no autorizados.

Esta defensa en profundidad parte de una premisa honesta: en un dispositivo que el usuario controla físicamente, ninguna protección a nivel de app es absoluta. El objetivo es **elevar el coste y la dificultad** de eludirla, no afirmar que sea imposible.

---

## Limitaciones conocidas

La transparencia sobre las limitaciones forma parte del proyecto:

- **Restricted Settings en Android 13+.** A partir de Android 13, el sistema bloquea la concesión del permiso de Accesibilidad a aplicaciones instaladas fuera de la Play Store (*sideload*) mediante la función "Restricted Settings". Esto afecta a la capa de bloqueo de ajustes en dispositivos modernos instalados por APK. Dispositivos con Android 10 (como el equipo de prueba principal) no se ven afectados. Este comportamiento está documentado en el modelo de amenazas del proyecto.
- **DNS privado del sistema (DoT).** Si el usuario configura un DNS privado a nivel de sistema operativo, este puede resolver consultas antes de que el tráfico entre en el túnel de la app. AdeoShield detecta esta situación y avisa, pero la mitigación completa depende de proteger esa pantalla de ajustes.
- **Acceso físico y modo seguro / fábrica.** Como cualquier control parental basado en el dispositivo, no protege frente a un restablecimiento de fábrica o el arranque en modo seguro por parte de alguien con acceso físico y conocimientos.

---

## Estado del proyecto

Proyecto funcional, probado en dispositivos reales (Samsung Galaxy J6 con Android 10 y Galaxy A54 con Android 13+). Incluye APK de *release* firmado. El desarrollo está documentado en un informe técnico que recorre las fases del proyecto y el modelo de amenazas.

---

## Licencia

Este proyecto se distribuye bajo licencia **GNU General Public License v3.0 (GPL-3.0)**. Ver el archivo [LICENSE](LICENSE) para el texto completo.

La GPL-3.0 garantiza que el código permanezca libre: cualquier trabajo derivado debe distribuirse también bajo los mismos términos, lo que evita que el proyecto sea absorbido en un producto cerrado.

---

## Autor

**Rafael Adiosdado Caballero Diéguez** — *Adeodato*
Analista de ciberseguridad (Blue Team / SOC), con trayectoria previa en el sector industrial (OT/ICS).

- LinkedIn: [rafael-adiosdado-caballero-diéguez](https://www.linkedin.com/in/rafael-adiosdado-caballero-di%C3%A9guez-16230b372/)
- Web: [adeodato.es](https://adeodato.es)

---
---

# AdeoShield (English)

**Android parental control based on DNS-over-HTTPS filtering, with settings lockdown and PIN protection.**

AdeoShield is a native Android parental-control app built in Kotlin and Jetpack Compose. It filters the device's network traffic through DNS-over-HTTPS (DoH) against AdGuard Family DNS, and prevents the filtering from being easily disabled through a PIN-protection system and lockdown of sensitive system settings screens.

> **Scope note:** AdeoShield is a personal and educational project, not a commercial product or a foolproof security solution. Like any on-device parental control, it is bound by the limitations of the Android platform. Its goal is to significantly raise the difficulty of bypassing the filter, not to guarantee the impossible.

## Key features

- **DNS-over-HTTPS filtering:** all device DNS traffic is routed through a local `VpnService` and resolved, encrypted, against AdGuard Family DNS.
- **PIN protection:** app access is protected by a PIN derived with `PBKDF2WithHmacSHA256` (120,000 iterations, per-install random salt). The PIN is never stored in plaintext.
- **Sensitive settings lockdown:** an Accessibility service detects and blocks access to system screens that could be used to disable the protection (Accessibility, VPN, Private DNS, device admin, developer options).
- **Configurable protections:** five protections enabled by default; the "System settings" lockdown is offered as an advanced, off-by-default option.
- **PIN-gated app blocking:** the parent selects, from the list of installed applications, which ones to protect. When someone tries to open a blocked app, AdeoShield intercepts the launch and requires the PIN before granting access.
- **System Private DNS detection:** the app detects when the OS-level private DNS (DoT) could intercept queries before they enter the tunnel, and warns the user.

## Tech stack

Kotlin · Jetpack Compose · `VpnService` + DNS-over-HTTPS · AdGuard Family DNS · PBKDF2WithHmacSHA256 · `AccessibilityService` (settings & app lockdown)

## Known limitations

- **Restricted Settings on Android 13+:** Android 13 and later block granting the Accessibility permission to sideloaded apps via "Restricted Settings", which affects the settings-lockdown layer on modern devices installed by APK. Android 10 devices are unaffected. This is documented in the project's threat model.
- **System Private DNS (DoT):** an OS-level private DNS may resolve queries before traffic enters the app's tunnel. AdeoShield detects and warns about this.
- **Physical access / safe mode / factory reset:** like any on-device parental control, it does not protect against a factory reset or safe-mode boot by someone with physical access.

## License

Distributed under the **GNU General Public License v3.0 (GPL-3.0)**. See [LICENSE](LICENSE).

## Author

**Rafael Adiosdado Caballero Diéguez** — *Adeodato*
Cybersecurity analyst (Blue Team / SOC), with a prior background in the industrial sector (OT/ICS).

- LinkedIn: [rafael-adiosdado-caballero-diéguez](https://www.linkedin.com/in/rafael-adiosdado-caballero-di%C3%A9guez-16230b372/)
- Web: [adeodato.es](https://adeodato.es)
