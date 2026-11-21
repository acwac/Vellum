package app.omit.shield.data

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class ShieldState {
    OFF,
    PROTECTED,
    PAUSED,
    OFFLINE_SANDBOX
}

data class DnsLogEntry(
    val id: Long,
    val timestampMs: Long,
    val domain: String,
    val qtype: String,
    val blocked: Boolean,
    val cached: Boolean,
    val cnameCloaked: Boolean,
    val reason: String,
    val latencyMs: Int
)

object VellumPrefs {
    private const val PREF_NAME = "vellum_shield_prefs_v2"

    @Volatile private var initialized = false
    private lateinit var prefs: SharedPreferences
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    // Hot-path atomic / volatile configuration (0 disk I/O during packet processing)
    val activeCategoryMask = AtomicInteger(FilterMetadata.MASK_BALANCED)
    @Volatile var desiredEnabled: Boolean = true
        private set
    @Volatile var offlineGameMode: Boolean = false
        private set
    @Volatile var upstreamId: String = "google"
        private set
    @Volatile var customDohUrl: String = "https://dns.google/dns-query"
        private set

    @Volatile var cnameUncloak: Boolean = true
        private set
    @Volatile var ispUnblockEnabled: Boolean = true
    @Volatile var smartContextMode: Boolean = true
    @Volatile var trapIpEnabled: Boolean = true
    @Volatile var homeShieldEnabled: Boolean = false
    @Volatile var antiStormTtl: Boolean = true
        private set
    @Volatile var blockDohCanary: Boolean = true
        private set
    @Volatile var captivePortalFallback: Boolean = true
        private set
    @Volatile var startOnBoot: Boolean = true
        private set
    @Volatile var liveLogEnabled: Boolean = true
        private set

    @Volatile var darkMode: Boolean = true
        private set

    @Volatile var customAllowDomains: Set<String> = emptySet()
        private set
    @Volatile var customBlockDomains: Set<String> = emptySet()
        private set
    @Volatile var customListUrls: Set<String> = emptySet()
        private set
    @Volatile var bypassPackages: Set<String> = emptySet()
        private set
    @Volatile var offlineGamePackages: Set<String> = emptySet()
        private set
    @Volatile var lastSyncedEpochMs: Long = 0L
        private set
    @Volatile var compiledRuleCount: Int = 209332
        private set
    @Volatile var isSyncingLists: Boolean = false
        private set

    // Live runtime telemetry
    @Volatile var shieldState: ShieldState = ShieldState.OFF
        private set
    @Volatile var pauseRemainingSec: Int = 0
        private set
    @Volatile var privateDnsHostWarning: String? = null
        private set

    val totalQueries = AtomicLong(0L)
    val blockedQueries = AtomicLong(0L)
    val cachedQueries = AtomicLong(0L)

    private val logSeq = AtomicLong(1L)
    private val logBuffer = ArrayDeque<DnsLogEntry>(260)
    private const val MAX_LOG_ENTRIES = 200
    private var lastUiLogNotifyMs = 0L

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext
            prefs = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            activeCategoryMask.set(prefs.getInt("category_mask", FilterMetadata.MASK_BALANCED))
            desiredEnabled = prefs.getBoolean("desired_enabled", true)
            offlineGameMode = prefs.getBoolean("offline_game_mode", false)
            upstreamId = prefs.getString("upstream_id", "google") ?: "google"
            customDohUrl = prefs.getString("custom_doh_url", "https://dns.google/dns-query") ?: "https://dns.google/dns-query"

            cnameUncloak = prefs.getBoolean("cname_uncloak", true)
            homeShieldEnabled = prefs.getBoolean("home_shield_enabled", false)
            antiStormTtl = prefs.getBoolean("anti_storm_ttl", true)
            blockDohCanary = prefs.getBoolean("block_doh_canary", true)
            captivePortalFallback = prefs.getBoolean("captive_portal_fallback", true)
            startOnBoot = prefs.getBoolean("start_on_boot", true)
            liveLogEnabled = prefs.getBoolean("live_log_enabled", true)

            val sysDark = (appCtx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            darkMode = prefs.getBoolean("dark_mode", true)

            customAllowDomains = readCleanSet("custom_allow")
            customBlockDomains = readCleanSet("custom_block")
            customListUrls = prefs.getStringSet("custom_urls", emptySet())?.toSet() ?: emptySet()
            bypassPackages = prefs.getStringSet("bypass_pkgs", emptySet())?.toSet() ?: emptySet()
            offlineGamePackages = prefs.getStringSet("offline_game_pkgs", emptySet())?.toSet() ?: emptySet()

            totalQueries.set(prefs.getLong("stat_total", 0L))
            blockedQueries.set(prefs.getLong("stat_blocked", 0L))
            cachedQueries.set(prefs.getLong("stat_cached", 0L))
            lastSyncedEpochMs = prefs.getLong("last_synced_ms", 0L)
            compiledRuleCount = prefs.getInt("compiled_rule_count", 209332)

            initialized = true
        }
    }

    fun consumeFirstLaunchAutoStart(): Boolean {
        if (!::prefs.isInitialized) return false
        val alreadyLaunched = prefs.getBoolean("first_launch_done", false)
        if (!alreadyLaunched) {
            prefs.edit().putBoolean("first_launch_done", true).apply()
            return true
        }
        return false
    }

    private fun readCleanSet(key: String): Set<String> {
        return prefs.getStringSet(key, emptySet())
            ?.map { it.trim().lowercase().trim('.') }
            ?.filter { it.isNotEmpty() }
            ?.toSet() ?: emptySet()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun notifyChanged() {
        if (listeners.isEmpty()) return
        mainHandler.post {
            for (l in listeners) {
                l.invoke()
            }
        }
    }

    fun setDarkModePref(dark: Boolean) {
        darkMode = dark
        prefs.edit().putBoolean("dark_mode", dark).apply()
        notifyChanged()
    }

    fun setCategoryMask(newMask: Int) {
        val clean = newMask and 0xFF
        activeCategoryMask.set(clean)
        prefs.edit().putInt("category_mask", clean).apply()
        notifyChanged()
    }

    fun toggleCategoryBit(bit: Int) {
        val next = activeCategoryMask.get() xor bit
        setCategoryMask(next)
    }

    fun setDesiredEnabled(enabled: Boolean) {
        desiredEnabled = enabled
        prefs.edit().putBoolean("desired_enabled", enabled).apply()
        notifyChanged()
    }

    fun setOfflineGameMode(active: Boolean) {
        offlineGameMode = active
        prefs.edit().putBoolean("offline_game_mode", active).apply()
        notifyChanged()
    }

    fun setUpstream(id: String, customUrl: String? = null) {
        upstreamId = id
        val ed = prefs.edit().putString("upstream_id", id)
        if (!customUrl.isNullOrBlank()) {
            val trimmed = customUrl.trim()
            customDohUrl = trimmed
            ed.putString("custom_doh_url", trimmed)
        }
        ed.apply()
        notifyChanged()
    }

    fun getEngineToggle(key: String): Boolean {
        return when (key) {
            "cname_uncloak" -> cnameUncloak
            "anti_storm_ttl" -> antiStormTtl
            "block_doh_canary" -> blockDohCanary
            "captive_portal_fallback" -> captivePortalFallback
            "start_on_boot" -> startOnBoot
            else -> false
        }
    }

    fun setHomeShieldEnabled(enabled: Boolean) {
        homeShieldEnabled = enabled
        prefs.edit().putBoolean("home_shield_enabled", enabled).apply()
        notifyChanged()
    }

    fun setEngineToggle(key: String, value: Boolean) {
        when (key) {
            "cname_uncloak" -> cnameUncloak = value
            "anti_storm_ttl" -> antiStormTtl = value
            "block_doh_canary" -> blockDohCanary = value
            "captive_portal_fallback" -> captivePortalFallback = value
            "start_on_boot" -> startOnBoot = value
        }
        prefs.edit().putBoolean(key, value).apply()
        notifyChanged()
    }

    fun setLiveLogEnabled(enabled: Boolean) {
        liveLogEnabled = enabled
        prefs.edit().putBoolean("live_log_enabled", enabled).apply()
        if (!enabled) clearLogs()
        notifyChanged()
    }

    fun setSyncing(syncing: Boolean) {
        isSyncingLists = syncing
        notifyChanged()
    }

    fun addAllowDomain(domain: String) {
        val clean = normalizeDomain(domain)
        if (clean.isEmpty()) return
        val nextAllow = customAllowDomains + clean
        val nextBlock = customBlockDomains - clean
        customAllowDomains = nextAllow
        customBlockDomains = nextBlock
        prefs.edit()
            .putStringSet("custom_allow", nextAllow)
            .putStringSet("custom_block", nextBlock)
            .apply()
        // Also remove from recent blocked display by updating log entry status if needed
        synchronized(logBuffer) {
            val updated = logBuffer.map {
                if (it.domain == clean || it.domain.endsWith(".$clean")) {
                    it.copy(blocked = false, reason = "Allowed Rule")
                } else it
            }
            logBuffer.clear()
            logBuffer.addAll(updated)
        }
        notifyChanged()
    }

    fun removeAllowDomain(domain: String) {
        val clean = normalizeDomain(domain)
        val next = customAllowDomains - clean
        customAllowDomains = next
        prefs.edit().putStringSet("custom_allow", next).apply()
        notifyChanged()
    }

    fun addBlockDomain(domain: String) {
        val clean = normalizeDomain(domain)
        if (clean.isEmpty()) return
        val nextBlock = customBlockDomains + clean
        val nextAllow = customAllowDomains - clean
        customBlockDomains = nextBlock
        customAllowDomains = nextAllow
        prefs.edit()
            .putStringSet("custom_block", nextBlock)
            .putStringSet("custom_allow", nextAllow)
            .apply()
        notifyChanged()
    }

    fun removeBlockDomain(domain: String) {
        val clean = normalizeDomain(domain)
        val next = customBlockDomains - clean
        customBlockDomains = next
        prefs.edit().putStringSet("custom_block", next).apply()
        notifyChanged()
    }

    fun addCustomListUrl(url: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) return
        val next = customListUrls + trimmed
        customListUrls = next
        prefs.edit().putStringSet("custom_urls", next).apply()
        notifyChanged()
    }

    fun removeCustomListUrl(url: String) {
        val next = customListUrls - url
        customListUrls = next
        prefs.edit().putStringSet("custom_urls", next).apply()
        notifyChanged()
    }

    fun setAppMode(pkg: String, mode: Int) {
        // 0 = Protected, 1 = Bypassed, 2 = Offline Game
        var nextBypass = bypassPackages - pkg
        var nextOffline = offlineGamePackages - pkg
        when (mode) {
            1 -> nextBypass = nextBypass + pkg
            2 -> nextOffline = nextOffline + pkg
        }
        bypassPackages = nextBypass
        offlineGamePackages = nextOffline
        if (nextOffline.isEmpty() && offlineGameMode) {
            offlineGameMode = false
            prefs.edit().putBoolean("offline_game_mode", false).apply()
        }
        prefs.edit()
            .putStringSet("bypass_pkgs", nextBypass)
            .putStringSet("offline_game_pkgs", nextOffline)
            .apply()
        notifyChanged()
    }

    fun getAppMode(pkg: String): Int {
        return when {
            bypassPackages.contains(pkg) -> 1
            offlineGamePackages.contains(pkg) -> 2
            else -> 0
        }
    }

    fun updateRuntimeState(state: ShieldState, pauseSec: Int = 0) {
        shieldState = state
        pauseRemainingSec = pauseSec
        notifyChanged()
    }

    fun updatePrivateDnsWarning(host: String?) {
        if (privateDnsHostWarning != host) {
            privateDnsHostWarning = host
            notifyChanged()
        }
    }

    fun updateSyncMetadata(ruleCount: Int) {
        compiledRuleCount = ruleCount
        lastSyncedEpochMs = System.currentTimeMillis()
        prefs.edit()
            .putInt("compiled_rule_count", ruleCount)
            .putLong("last_synced_ms", lastSyncedEpochMs)
            .apply()
        notifyChanged()
    }

    fun recordQuery(
        domain: String,
        qtype: String,
        blocked: Boolean,
        cached: Boolean,
        cnameCloaked: Boolean,
        reason: String,
        latencyMs: Int
    ) {
        val tot = totalQueries.incrementAndGet()
        if (blocked) blockedQueries.incrementAndGet()
        if (cached) cachedQueries.incrementAndGet()

        if (tot % 200L == 0L && ::prefs.isInitialized) {
            prefs.edit()
                .putLong("stat_total", tot)
                .putLong("stat_blocked", blockedQueries.get())
                .putLong("stat_cached", cachedQueries.get())
                .apply()
        }

        if (liveLogEnabled) {
            val now = System.currentTimeMillis()
            val entry = DnsLogEntry(
                id = logSeq.getAndIncrement(),
                timestampMs = now,
                domain = domain,
                qtype = qtype,
                blocked = blocked,
                cached = cached,
                cnameCloaked = cnameCloaked,
                reason = reason,
                latencyMs = latencyMs
            )
            val shouldNotify: Boolean
            synchronized(logBuffer) {
                logBuffer.addFirst(entry)
                while (logBuffer.size > MAX_LOG_ENTRIES) {
                    logBuffer.removeLast()
                }
                shouldNotify = listeners.isNotEmpty() && (now - lastUiLogNotifyMs) >= 500L
                if (shouldNotify) lastUiLogNotifyMs = now
            }
            if (shouldNotify) {
                notifyChanged()
            }
        }
    }

    fun getLogsSnapshot(): List<DnsLogEntry> {
        synchronized(logBuffer) {
            return logBuffer.toList()
        }
    }

    fun clearLogs() {
        synchronized(logBuffer) {
            logBuffer.clear()
        }
        notifyChanged()
    }

    fun flushStats() {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .putLong("stat_total", totalQueries.get())
            .putLong("stat_blocked", blockedQueries.get())
            .putLong("stat_cached", cachedQueries.get())
            .apply()
    }

    fun normalizeDomain(raw: String): String {
        var s = raw.trim().lowercase()
        if (s.startsWith("https://")) s = s.removePrefix("https://")
        if (s.startsWith("http://")) s = s.removePrefix("http://")
        val slash = s.indexOf('/')
        if (slash >= 0) s = s.substring(0, slash)
        return s.trim('.').trim()
    }

    data class SpyStat(
        val packageName: String,
        val appName: String,
        var spyCount: Long = 0L,
        var bgWakeups: Long = 0L,
        var lastDomain: String = "",
        var lastSeenMs: Long = 0L
    )

    private val spyStatsMap = java.util.concurrent.ConcurrentHashMap<String, SpyStat>()

    fun recordSpyEvent(packageName: String, appName: String, domain: String, isBackground: Boolean) {
        val stat = spyStatsMap.getOrPut(packageName) {
            SpyStat(packageName = packageName, appName = appName)
        }
        stat.spyCount++
        if (isBackground) stat.bgWakeups++
        stat.lastDomain = domain
        stat.lastSeenMs = System.currentTimeMillis()
        notifyChanged()
    }

    fun getSpyStatsSnapshot(): List<SpyStat> {
        return spyStatsMap.values.sortedByDescending { it.bgWakeups * 10 + it.spyCount }
    }
}
