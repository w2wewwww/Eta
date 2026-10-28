package fuck.andes.hook.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationClickZoomPolicyTest {
    @Test
    fun onlySupportedRegularActivityNotificationsUseZoom() {
        assertTrue(
            NotificationClickZoomPolicy.shouldPromote(
                isActivity = true,
                isBubble = false,
                alreadyZoomPath = false,
                resizeMode = 4,
            )
        )
    }

    @Test
    fun supportedZoomTapUsesUpwardExitVelocityOnly() {
        val velocityY = NotificationClickZoomPolicy.tapExitVelocityY(
            isActivity = true,
            isBubble = false,
            alreadyZoomPath = false,
            resizeMode = 4,
        )
        assertTrue(velocityY != null && velocityY >= 6000f)
    }

    @Test
    fun supportedZoomLaunchEnablesAnimationAndZoomWithoutChangingLockscreenFlag() {
        val args = arrayOfNulls<Any>(7)
        args[4] = false
        args[5] = false
        args[6] = false

        assertTrue(NotificationClickZoomPolicy.promoteLaunchArguments(args))

        assertFalse(args[4] as Boolean)
        assertTrue(args[5] as Boolean)
        assertTrue(args[6] as Boolean)
    }

    @Test
    fun promoteLaunchArgumentsRejectsUnexpectedSignature() {
        assertFalse(NotificationClickZoomPolicy.promoteLaunchArguments(arrayOfNulls(6)))
    }

    @Test
    fun unsupportedTargetsKeepTheFullscreenPath() {
        listOf(0, 1, 3, 5).forEach { resizeMode ->
            assertFalse(
                NotificationClickZoomPolicy.shouldPromote(
                    isActivity = true,
                    isBubble = false,
                    alreadyZoomPath = false,
                    resizeMode = resizeMode,
                )
            )
            assertTrue(
                NotificationClickZoomPolicy.tapExitVelocityY(
                    isActivity = true,
                    isBubble = false,
                    alreadyZoomPath = false,
                    resizeMode = resizeMode,
                ) == null
            )
        }
    }

    @Test
    fun nonActivityBubbleAndExistingZoomLaunchesAreUnchanged() {
        assertFalse(shouldPromote(isActivity = false, isBubble = false, alreadyZoomPath = false))
        assertFalse(shouldPromote(isActivity = true, isBubble = true, alreadyZoomPath = false))
        assertFalse(shouldPromote(isActivity = true, isBubble = false, alreadyZoomPath = true))
        assertFalse(shouldPromote(isActivity = true, isBubble = false, alreadyZoomPath = null))
    }

    private fun shouldPromote(
        isActivity: Boolean,
        isBubble: Boolean,
        alreadyZoomPath: Boolean?,
    ) = NotificationClickZoomPolicy.shouldPromote(
        isActivity = isActivity,
        isBubble = isBubble,
        alreadyZoomPath = alreadyZoomPath,
        resizeMode = 4,
    )
}
