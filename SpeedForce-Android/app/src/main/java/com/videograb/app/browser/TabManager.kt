package com.videograb.app.browser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue

/** Simple list of browser tabs. */
class TabManager(private val context: Context, private val listener: TabListener) {
    companion object {
        const val INACTIVE_MS = 30L * 24 * 3600 * 1000
        fun isInactive(lastUsedAt: Long, now: Long = System.currentTimeMillis()): Boolean = now - lastUsedAt >= INACTIVE_MS
    }
    val tabs = mutableStateListOf<BrowserTab>()
    var currentIndex by mutableIntStateOf(0)
    private var nextId = 1

    val current: BrowserTab get() = tabs[currentIndex.coerceIn(0, tabs.size - 1)]

    /** A tab for a popup: made now, put on screen by [show] only if it turns out to be wanted. */
    fun popupTab(): BrowserTab = BrowserTab(nextId++, context, listener)

    fun show(tab: BrowserTab) {
        if (tabs.isNotEmpty()) { current.capture(); current.pause() }
        tabs.add(tab)
        currentIndex = tabs.size - 1
    }

    fun newTab(url: String): BrowserTab {
        val tab = BrowserTab(nextId++, context, listener)
        if (tabs.isNotEmpty()) { current.capture(); current.pause() }
        tabs.add(tab)
        currentIndex = tabs.size - 1
        tab.load(url)
        return tab
    }

    /**
     * Bring back the tabs from last time. Only the front tab loads now; the others load
     * when you open them (so starting the app stays fast).
     */
    fun restore(urls: List<String>, front: Int, times: List<Long> = emptyList()) {
        val f = front.coerceIn(0, urls.size - 1)
        val now = System.currentTimeMillis()
        urls.forEachIndexed { i, u ->
            val tab = BrowserTab(nextId++, context, listener)
            tab.lastUsedAt = if (i == f) now else times.getOrElse(i) { now }
            tabs.add(tab)
            if (i == f) tab.load(u) else tab.loadLater(u)
        }
        currentIndex = f
    }

    fun select(index: Int) {
        if (index in tabs.indices) {
            if (index != currentIndex) { current.capture(); current.pause() }
            currentIndex = index
            tabs[index].lastUsedAt = System.currentTimeMillis()
            tabs[index].resume()
            tabs[index].loadPending()
        }
    }

    private val crashTimes = ArrayDeque<Long>()

    /**
     * The page engine died, usually because the phone ran out of memory. Every tab dies with it, so
     * each one is replaced where it was. Only the tab in front reloads (reloading all of them at once
     * is what would run out of memory again); after repeated crashes it doesn't reload by itself either.
     * Returns false when the page was not reloaded.
     */
    fun replaceCrashed(old: BrowserTab, homeUrl: String): Boolean {
        val index = tabs.indexOf(old)
        if (index < 0) return true
        val url = old.pendingUrl ?: old.url
        val tab = BrowserTab(nextId++, context, listener)
        tabs[index] = tab
        old.destroy()
        if (index != currentIndex) { if (url.isNotBlank()) tab.loadLater(url); return true }
        val now = System.currentTimeMillis()
        crashTimes.addLast(now)
        while (crashTimes.first() < now - 60_000) crashTimes.removeFirst()
        if (crashTimes.size > 2) { tab.load(homeUrl); return false }
        tab.load(url.ifBlank { homeUrl })
        return true
    }

    /** Addresses of all open tabs (for reopening them next time). */
    fun urls(): List<String> = tabs.map { it.pendingUrl ?: it.url }

    fun times(): List<Long> = tabs.map { it.lastUsedAt }

    fun inactiveTabs(now: Long = System.currentTimeMillis()): List<BrowserTab> =
        tabs.filterIndexed { i, t -> i != currentIndex && isInactive(t.lastUsedAt, now) }

    fun closeInactive(homeUrl: String, now: Long = System.currentTimeMillis()) {
        for (i in tabs.indices.reversed()) {
            if (i != currentIndex && isInactive(tabs[i].lastUsedAt, now)) close(i, homeUrl)
        }
    }

    fun hasOpener(tab: BrowserTab): Boolean = tab.openerId?.let { id -> tabs.any { it.id == id } } == true

    fun backToOpener(tab: BrowserTab, homeUrl: String) {
        val openerIdx = tab.openerId?.let { id -> tabs.indexOfFirst { it.id == id } } ?: -1
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        if (openerIdx >= 0) {
            currentIndex = openerIdx
            tabs[openerIdx].resume()
            tabs[openerIdx].loadPending()
        }
        close(idx, homeUrl)
    }

    fun close(index: Int, homeUrl: String) {
        if (index !in tabs.indices) return
        val closing = tabs.removeAt(index)
        CookieGuard.tabClosed(closing.visited, urls())
        closing.destroy()
        if (tabs.isEmpty()) newTab(homeUrl)
        if (index < currentIndex) currentIndex--
        currentIndex = currentIndex.coerceIn(0, tabs.size - 1)
        tabs[currentIndex].loadPending()
    }

    fun destroyAll() {
        tabs.forEach { it.destroy() }
        tabs.clear()
    }
}
