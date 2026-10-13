package com.arceusvault.keel.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.arceusvault.keel.MainActivity
import com.arceusvault.keel.R
import com.arceusvault.keel.core.network.RawApiKey
import com.arceusvault.keel.core.network.Scram
import com.arceusvault.keel.core.security.KeystoreKeyProvider
import com.arceusvault.keel.core.security.Vault
import com.arceusvault.keel.core.security.VaultContents
import com.arceusvault.keel.domain.ServerProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Opt-in background alert checks. They can't use the main vault, whose key needs a fresh unlock,
 * so they use a second API key that the server lets read alerts and nothing else. Its derived
 * SCRAM keys sit in their own vault under a Keystore key that works without user auth.
 */
class AlertWatch(private val context: Context, private val json: Json, private val connector: Connector) {
    private val vault = Vault.single(
        File(context.noBackupFilesDir, "notify.bin"),
        KeystoreKeyProvider(context, KeystoreKeyProvider.NOTIFY_ALIAS, authBound = false),
        json,
    )
    private val prefs = context.getSharedPreferences("alert_watch", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(vault.exists())
    val enabled: StateFlow<Boolean> = _enabled

    init {
        // Re-assert the schedule on every start; schedule() replaces an existing job, so this is idempotent.
        if (_enabled.value) runCatching { schedule() }
    }

    /** Lowest level that notifies. TrueNAS levels, most to least severe. */
    var minLevel: String
        get() = prefs.getString(KEY_LEVEL, "WARNING") ?: "WARNING"
        set(v) = prefs.edit { putString(KEY_LEVEL, v) }

    /** What the widget shows: active alert counts per level from the last successful check. */
    data class Summary(val counts: Map<String, Int>, val checkedAt: Long, val host: String)

    fun summary(): Summary? {
        val at = prefs.getLong(KEY_SUM_TIME, 0).takeIf { it > 0 } ?: return null
        val counts = prefs.getString(KEY_SUM_COUNTS, "").orEmpty().split(',').mapNotNull { e ->
            val (level, n) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            n.toIntOrNull()?.let { level to it }
        }.toMap()
        return Summary(counts, at, prefs.getString(KEY_SUM_HOST, null) ?: "TrueNAS")
    }

    val lastCheck: Long get() = prefs.getLong(KEY_LAST, 0)
    val lastError: String? get() = prefs.getString(KEY_ERROR, null)

    /**
     * Logs in with the alert key, refuses it unless alert reading is all it can do, then stores it and
     * schedules checks. [profile] supplies the address, connection mode and pinned certificate.
     */
    suspend fun enable(profile: ServerProfile, username: String, raw: RawApiKey) {
        val result = try {
            val rpc = connector.open(profile.url, profile.trust, profile.mode)
            connector.login(rpc, username, raw.id, KeyMaterial.Raw(raw))
        } finally {
            raw.wipe()
        }
        try {
            val extra = result.roles.granted - ALLOWED
            if (result.roles.fullAdmin || extra.isNotEmpty()) {
                throw LoginException(
                    "This key can do more than read alerts (${extra.ifEmpty { setOf("FULL_ADMIN") }.joinToString()}). " +
                        "It would be stored without a fingerprint lock, so the app only accepts a key whose privilege has just ALERT_LIST_READ.",
                )
            }
            if (result.roles.granted.isEmpty()) throw LoginException("This key has no roles, so it can't read alerts. Give its privilege ALERT_LIST_READ.")
            // Alerts that exist now are already visible in the app; only new ones should notify.
            val current = json.decodeFromJsonElement(ListSerializer(Alert.serializer()), result.rpc.call("alert.list"))
            vault.write(
                VaultContents(
                    profile.copy(username = username, apiKeyId = raw.id),
                    result.keys.salt, result.keys.iterations,
                    Base64.getEncoder().encodeToString(result.keys.clientKey),
                    Base64.getEncoder().encodeToString(result.keys.serverKey),
                ),
            )
            prefs.edit { putStringSet(KEY_SEEN, current.map { it.uuid }.toSet()); remove(KEY_ERROR) }
            schedule()
        } catch (e: Exception) {
            // Never leave an unscheduled or rejected key on disk.
            vault.delete()
            throw e
        } finally {
            result.rpc.close()
        }
        _enabled.value = true
        AlertWidget.refreshAll(context)
    }

    fun disable() {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        vault.delete()
        prefs.edit { clear() }
        _enabled.value = false
        AlertWidget.refreshAll(context)
    }

    /** Follows a re-trusted certificate on the server the alert key belongs to. */
    fun updateProfile(profile: ServerProfile) {
        val c = runCatching { vault.read() }.getOrNull() ?: return
        if (c.profile.url != profile.url || (c.profile.trust == profile.trust && c.profile.mode == profile.mode)) return
        vault.write(c.copy(profile = c.profile.copy(mode = profile.mode, trust = profile.trust)))
    }

    /** Turns notifications off if [profile]'s server is the one the alert key belongs to. */
    fun serverRemoved(profile: ServerProfile) {
        val c = runCatching { vault.read() }.getOrNull()
        if (c == null || c.profile.url == profile.url) disable()
    }

    fun schedule() {
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, AlertJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(TimeUnit.MINUTES.toMillis(15))
            .setPersisted(true)
            .build()
        context.getSystemService(JobScheduler::class.java).schedule(job)
    }

    /** One background check. Returns how many notifications were posted. */
    suspend fun check(): Int {
        val c = vault.read() ?: return 0
        return try {
            val rpc = connector.open(c.profile.url, c.profile.trust, c.profile.mode)
            val keys = Scram.DerivedKeys(
                c.scramSalt, c.scramIterations, Base64.getDecoder().decode(c.clientKey), Base64.getDecoder().decode(c.serverKey),
            )
            val result = connector.login(rpc, c.profile.username, c.profile.apiKeyId, KeyMaterial.Derived(keys))
            val alerts = try {
                json.decodeFromJsonElement(ListSerializer(Alert.serializer()), result.rpc.call("alert.list"))
            } finally {
                result.rpc.close()
            }
            val posted = notifyNew(alerts)
            val counts = alerts.filterNot { it.dismissed }.groupingBy { it.level.uppercase(Locale.ROOT) }.eachCount()
            prefs.edit {
                putLong(KEY_LAST, System.currentTimeMillis())
                remove(KEY_ERROR)
                putLong(KEY_SUM_TIME, System.currentTimeMillis())
                putString(KEY_SUM_COUNTS, counts.entries.joinToString(",") { "${it.key}:${it.value}" })
                putString(KEY_SUM_HOST, c.profile.host)
            }
            AlertWidget.refreshAll(context)
            posted
        } catch (e: Exception) {
            prefs.edit { putLong(KEY_LAST, System.currentTimeMillis()); putString(KEY_ERROR, e.message ?: e.javaClass.simpleName) }
            AlertWidget.refreshAll(context)
            throw e
        }
    }

    internal fun notifyNew(alerts: List<Alert>): Int {
        val fresh = select(alerts, prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty(), minLevel)
        // Remember everything currently present, so the set never grows past the server's alert list.
        prefs.edit { putStringSet(KEY_SEEN, alerts.map { it.uuid }.toSet()) }
        if (fresh.isEmpty() || !canNotify()) return 0
        ensureChannel()
        val nm = context.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fresh.forEach { a ->
            // The lock screen only shows that there is an alert, not what it says.
            val public = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Server alert")
                .build()
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("${a.level.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }} on ${hostOf()}")
                .setContentText(a.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(a.message))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(if (levelIndex(a.level) <= LEVELS.indexOf("ERROR")) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(a.uuid.hashCode(), n)
        }
        return fresh.size
    }

    private fun hostOf(): String = runCatching { vault.read()?.profile?.host }.getOrNull() ?: "TrueNAS"

    // Android 13 added a runtime permission; before that only the user's per-app toggle matters.
    fun canNotify(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Server alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New TrueNAS alerts, checked every 15 minutes"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            },
        )
    }

    companion object {
        const val JOB_ID = 4201
        private const val CHANNEL = "alerts"
        private const val KEY_SEEN = "seen"
        private const val KEY_LEVEL = "min_level"
        private const val KEY_LAST = "last_check"
        private const val KEY_ERROR = "last_error"
        private const val KEY_SUM_TIME = "summary_time"
        private const val KEY_SUM_COUNTS = "summary_counts"
        private const val KEY_SUM_HOST = "summary_host"
        val ALLOWED = setOf("ALERT_LIST_READ")
        val LEVELS = listOf("EMERGENCY", "ALERT", "CRITICAL", "ERROR", "WARNING", "NOTICE", "INFO")

        /** Alerts worth a notification: new since last check, not dismissed, and at least [minLevel]. */
        fun select(alerts: List<Alert>, seen: Set<String>, minLevel: String): List<Alert> {
            val threshold = LEVELS.indexOf(minLevel).takeIf { it >= 0 } ?: LEVELS.indexOf("WARNING")
            return alerts.filter { !it.dismissed && it.uuid !in seen && levelIndex(it.level) <= threshold }
        }

        fun levelIndex(level: String) = LEVELS.indexOf(level.uppercase(Locale.ROOT)).let { if (it < 0) LEVELS.size else it }
    }
}
