package com.trackmysugar

import android.content.ComponentName
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app icon, name and launch screen are wired up (artwork comes from branding/make_assets.py). */
class BrandingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pm = context.packageManager

    @Test
    fun appIsNamedTrackMySugar() {
        assertEquals("TrackMySugar", pm.getApplicationLabel(context.applicationInfo).toString())
    }

    @Test
    fun versionComesFromTheSharedVersionFile() {
        val version = context.assets.open("public/version.txt").bufferedReader().use { it.readText() }.trim()
        assertTrue(version, Regex("\\d+\\.\\d+\\.\\d+").matches(version))
        val info = pm.getPackageInfo(context.packageName, 0)
        assertEquals(version, info.versionName)
        val (major, minor, patch) = version.split(".").map { it.toInt() }
        assertEquals((major * 10000 + minor * 100 + patch).toLong(), info.longVersionCode)
    }

    @Test
    fun launcherIconIsAnAdaptiveIconWithAThemedVersion() {
        val icon = pm.getApplicationIcon(context.packageName)
        assertTrue("adaptive icon, was ${icon.javaClass}", icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        assertNotNull(icon.background)
        assertNotNull(icon.foreground)
        if (Build.VERSION.SDK_INT >= 33) assertNotNull("monochrome layer for themed icons", icon.monochrome)
    }

    @Test
    fun mainActivityStartsWithTheLaunchScreenTheme() {
        val info = pm.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(R.style.Theme_App_Starting, info.theme)
    }

    @Test
    fun launchScreenArtworkIsPackaged() {
        val res = context.resources
        assertNotNull(res.getDrawable(R.drawable.splash_icon, null))
        assertNotNull(res.getDrawable(R.drawable.splash_branding, null))
    }
}
