package es.adeodato.dnsguardian.security

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Respaldo del sondeo de AccessibilityWatchdog.check() -- ver
 * DnsVpnService (sondeo principal, cada 60s, pero solo corre mientras la
 * VPN esta viva). Cierra, en la medida de lo posible, el hueco "VPN
 * parada Y Accesibilidad desactivada a la vez" (ver limitacion de
 * dependencia circular documentada en AccessibilityWatchdog.kt), a costa
 * de mucha menos frecuencia: 15 min es el minimo que permite WorkManager
 * para trabajos periodicos.
 *
 * check() es idempotente (su propio estado vive en KEY_WARNING_SHOWN),
 * asi que no hay problema en que este Worker y el sondeo de DnsVpnService
 * se solapen o disparen casi a la vez -- en el peor caso ambos leen
 * "aviso no mostrado", ambos llaman a mostrarAviso() con el mismo
 * NOTIF_ID (se sustituye, no se duplica) y ambos escriben el mismo valor
 * en la preferencia. Sin bloqueo necesario.
 *
 * LIMITACION (Samsung / One UI y otros OEM con gestion agresiva de
 * bateria): Doze mode y los limites de "apps en segundo plano" pueden
 * retrasar la ejecucion de este Worker mucho mas alla de los 15 min
 * pedidos, o no ejecutarlo si la app queda hibernada/restringida.
 * WorkManager no puede forzar la ejecucion puntual bajo Doze; esto es una
 * red de respaldo de mejor esfuerzo, no una garantia. La deteccion
 * principal (DnsVpnService, mientras la VPN esta activa) no depende de
 * esto.
 *
 * NOTA PARA PROBAR ESTO EN UN DISPOSITIVO REAL: en un Samsung A57 (One
 * UI), `adb shell cmd jobscheduler run -f <pkg> <job_id>` sobre el job
 * periodico YA encolado no es fiable -- en varias pruebas, dumpsys
 * jobscheduler mostraba el ciclo START/STOP "app called jobFinished"
 * (contabilidad del propio JobScheduler) sin que doWork() llegara a
 * ejecutarse de verdad (sin logs propios, sin entradas WM-* de
 * WorkManager). Para probar el codigo de este Worker de forma fiable,
 * encolar un OneTimeWorkRequest<AccessibilityWatchdogWorker> aparte (sin
 * restricciones, se ejecuta casi al instante) en vez de forzar el job
 * periodico existente.
 */
class AccessibilityWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        AccessibilityWatchdog.check(applicationContext)
        return Result.success()
    }
}
