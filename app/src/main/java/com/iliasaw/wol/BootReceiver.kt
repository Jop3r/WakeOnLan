package com.iliasaw.wol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Стартует сервис после перезагрузки телефона или обновления приложения,
 * если тумблер мониторинга был включён.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        if (!Prefs(context).monitoringEnabled) return
        WolForegroundService.start(context)
    }
}
