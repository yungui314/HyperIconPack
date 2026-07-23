package io.github.cl0ura.hypericonpack.systemtheme

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingPackageChangeResolverTest {
    @Test
    fun `current installation state wins over stale queue direction`() {
        val resolved = PendingPackageChangeResolver.resolve(
            pendingUpdates = listOf("com.example.removed", "com.example.present"),
            pendingRemovals = listOf("com.example.reinstalled"),
            installedPackageNames = setOf("com.example.present", "com.example.reinstalled"),
            sourcePackageName = "com.example.icons",
        )

        assertEquals(listOf("com.example.present", "com.example.reinstalled"), resolved.updates)
        assertEquals(listOf("com.example.removed"), resolved.removals)
    }

    @Test
    fun `source package and duplicate queue entries are ignored`() {
        val resolved = PendingPackageChangeResolver.resolve(
            pendingUpdates = listOf("com.example.icons", "com.example.app"),
            pendingRemovals = listOf("com.example.app", "com.example.icons"),
            installedPackageNames = setOf("com.example.icons", "com.example.app"),
            sourcePackageName = "com.example.icons",
        )

        assertEquals(listOf("com.example.app"), resolved.updates)
        assertEquals(emptyList<String>(), resolved.removals)
    }
}
