package com.jqc.webplayer.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeSharedPreferences : SharedPreferences {
    val values = mutableMapOf<String, String?>()

    override fun getString(key: String, defValue: String?): String? =
        values[key] ?: defValue

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun apply() {
            values.putAll(pending)
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun putStringSet(key: String, values: MutableSet<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
        override fun remove(key: String) = this
        override fun clear() = this
    }

    override fun getAll(): MutableMap<String, *> = values
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = defValue
    override fun getLong(key: String, defValue: Long) = defValue
    override fun getFloat(key: String, defValue: Float) = defValue
    override fun getBoolean(key: String, defValue: Boolean) = defValue
    override fun contains(key: String) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = Unit
}

class SiteMemoryRepositoryTest {
    @Test
    fun desktopOverrideIsRememberedPerSite() {
        val repo = SiteMemoryRepository(FakeSharedPreferences())

        repo.setDesktopUa("a.com", true)

        assertEquals(SitePrefs(desktopUa = true), repo.get("a.com"))
        assertNull(repo.get("b.com"))
    }

    @Test
    fun overridesCoexistAndClearRestoresDefault() {
        val repo = SiteMemoryRepository(FakeSharedPreferences())

        repo.setDesktopUa("a.com", true)
        repo.setBlockPopups("a.com", false)
        repo.setBlockPopups("b.com", true)

        assertEquals(SitePrefs(desktopUa = true, blockPopups = false), repo.get("a.com"))
        assertEquals(SitePrefs(blockPopups = true), repo.get("b.com"))

        repo.clear("a.com")

        assertNull(repo.get("a.com"))
        assertEquals(SitePrefs(blockPopups = true), repo.get("b.com"))
    }

    @Test
    fun clearingOnlyOverrideDropsTheSiteEntry() {
        val repo = SiteMemoryRepository(FakeSharedPreferences())

        repo.setDesktopUa("a.com", true)
        repo.setDesktopUa("a.com", null)

        assertNull(repo.get("a.com"))
    }

    @Test
    fun malformedStoredDataIsIgnored() {
        val prefs = FakeSharedPreferences()
        prefs.values["site_prefs"] = "broken;;a.com=1,-;=1,0;b.com=x,2"

        val repo = SiteMemoryRepository(prefs)

        assertEquals(SitePrefs(desktopUa = true), repo.get("a.com"))
        assertNull(repo.get("b.com"))
        assertNull(repo.get(""))
    }
}
