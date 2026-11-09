package com.yichao.evilgodxu.screens.settings

import com.yichao.evilgodxu.data.music.proxy.ProxySourceSpec
import com.yichao.evilgodxu.data.settings.AppLanguage

// 内存版平台能力：记录调用而不触达系统，用于验证 ViewModel 的判定与状态流转
class FakeSettingsEnvironment(
    override val appVersion: String = "6.2.3",
    var overlayGranted: Boolean = true,
    var notificationPolicyGranted: Boolean = true,
    var cacheBytes: Long = 0L,
    var importResult: ProxyImportMessage = ProxyImportMessage(text = null, failed = false),
) : SettingsEnvironment {

    var usbAccessRequested = false
    var overlayMonitorStarted = false
    var overlayMonitorStopped = false
    var notificationPolicyWatched = false
    var appliedLanguage: AppLanguage? = null
    var proxyChangesRegistered = false
    var proxyChangesUnregistered = false

    private var sources: List<ProxySourceSpec> = emptyList()
    private var onProxyChange: (() -> Unit)? = null

    fun setSources(value: List<ProxySourceSpec>) {
        sources = value
    }

    fun notifyProxyChanged() {
        onProxyChange?.invoke()
    }

    override fun proxySources(): List<ProxySourceSpec> = sources

    override fun registerProxySourceChanges(onChange: () -> Unit) {
        proxyChangesRegistered = true
        onProxyChange = onChange
    }

    override fun unregisterProxySourceChanges() {
        proxyChangesUnregistered = true
        onProxyChange = null
    }

    override suspend fun importProxySource(content: String): ProxyImportMessage = importResult

    override suspend fun setProxySourceEnabled(name: String, enabled: Boolean) = Unit

    override suspend fun removeProxySource(name: String) = Unit

    override suspend fun sampleCacheBytes(): Long = cacheBytes

    override fun applyLanguage(language: AppLanguage) {
        appliedLanguage = language
    }

    override fun canDrawOverlays(): Boolean = overlayGranted

    override fun startOverlayGrantMonitor() {
        overlayMonitorStarted = true
    }

    override fun stopOverlayGrantMonitor() {
        overlayMonitorStopped = true
    }

    override fun isNotificationPolicyGranted(): Boolean = notificationPolicyGranted

    override fun watchNotificationPolicyGrant() {
        notificationPolicyWatched = true
    }

    override fun requestUsbAccess() {
        usbAccessRequested = true
    }
}
