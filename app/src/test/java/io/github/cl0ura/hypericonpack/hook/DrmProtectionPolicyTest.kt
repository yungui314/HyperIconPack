package io.github.cl0ura.hypericonpack.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrmProtectionPolicyTest {
    @Test
    fun `protects managed icons and dynamicicons only when marker exists`() {
        assertTrue(
            DrmProtectionPolicy.shouldBypass(
                contentPath = "/data/system/theme/icons",
                managedThemeMarkerPresent = true,
            ),
        )
        assertTrue(
            DrmProtectionPolicy.shouldBypass(
                contentPath = "/data/system/theme/dynamicicons",
                managedThemeMarkerPresent = true,
            ),
        )
    }

    @Test
    fun `does not protect theme components when this app is not managing icons`() {
        assertFalse(
            DrmProtectionPolicy.shouldBypass(
                contentPath = "/data/system/theme/icons",
                managedThemeMarkerPresent = false,
            ),
        )
        assertFalse(
            DrmProtectionPolicy.shouldBypass(
                contentPath = "/data/system/theme/lockscreen",
                managedThemeMarkerPresent = true,
            ),
        )
        assertFalse(
            DrmProtectionPolicy.shouldBypass(
                contentPath = "/data/system/theme/rights/icons_default.mra",
                managedThemeMarkerPresent = true,
            ),
        )
    }

    @Test
    fun `normalizes trailing slashes and accepts absolute managed paths`() {
        assertTrue(
            DrmProtectionPolicy.shouldBypass(
                contentPath = "/data/system/theme/icons/",
                managedThemeMarkerPresent = true,
            ),
        )
    }
}
