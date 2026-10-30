package fuck.andes.hook.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlexibleWindowFullscreenPolicyTest {
    @Test
    fun smartSidebarAndGameSidebarKeepNativeZoomMode() {
        assertTrue(
            FlexibleWindowFullscreenPolicy.shouldPreserveSidebarZoom(
                callingPackage = "com.coloros.smartsidebar",
                launchWindowingMode = 100,
            )
        )
        assertTrue(
            FlexibleWindowFullscreenPolicy.shouldPreserveSidebarZoom(
                callingPackage = "com.oplus.games",
                launchWindowingMode = 100,
            )
        )
    }

    @Test
    fun unrelatedOrNonZoomLaunchesRemainFullscreen() {
        assertFalse(
            FlexibleWindowFullscreenPolicy.shouldPreserveSidebarZoom(
                callingPackage = "com.example.app",
                launchWindowingMode = 100,
            )
        )
        assertFalse(
            FlexibleWindowFullscreenPolicy.shouldPreserveSidebarZoom(
                callingPackage = "com.coloros.smartsidebar",
                launchWindowingMode = 0,
            )
        )
        assertFalse(
            FlexibleWindowFullscreenPolicy.shouldPreserveSidebarZoom(
                callingPackage = null,
                launchWindowingMode = 100,
            )
        )
    }
}
