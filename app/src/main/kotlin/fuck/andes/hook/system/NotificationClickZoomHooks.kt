package fuck.andes.hook.system

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import fuck.andes.core.HookInstallation
import fuck.andes.core.HookRegistrar
import fuck.andes.core.HookSupport
import fuck.andes.core.ModuleLogger
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/** Route regular notification taps through ColorOS' native zoom path only when its own
 * flexible-window classifier says ResizeMode=4. Unsupported targets stay fullscreen. */
internal object NotificationClickZoomHooks {
    private const val STARTER =
        "com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter"
    private const val ENTRY =
        "com.android.systemui.statusbar.notification.collection.NotificationEntry"
    private const val ROW =
        "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
    private const val RESIZE_MODE = "androidx.flexible.ResizeMode"
    private const val ZOOM_CURRENT_SPEED_Y = "key_zoom_current_speed_y"
    private const val ZOOM_ARG = 6

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "SystemUI/NotificationClickZoom")
        return hooks.install {
            val starterClass = HookSupport.findClassOrNull(classLoader, STARTER)
            val entryClass = HookSupport.findClassOrNull(classLoader, ENTRY)
            val rowClass = HookSupport.findClassOrNull(classLoader, ROW)
            val method = if (starterClass != null && entryClass != null && rowClass != null) {
                HookSupport.findMethod(
                    starterClass,
                    "startNotificationIntent",
                    PendingIntent::class.java,
                    Intent::class.java,
                    entryClass,
                    rowClass,
                    Boolean::class.javaPrimitiveType!!,
                    Boolean::class.javaPrimitiveType!!,
                    Boolean::class.javaPrimitiveType!!,
                )
            } else null

            if (method == null) {
                hooks.missing(
                    id = "systemui.notification-click-zoom",
                    description = "StatusBarNotificationActivityStarter.startNotificationIntent",
                    detail = "未找到通知启动入口；保持系统默认全屏行为",
                )
                return@install
            }

            hooks.intercept(
                id = "systemui.notification-click-zoom",
                executable = method,
                description = "支持 flexible 的通知点击使用原生小窗参数",
            ) { chain ->
                val args = chain.args.toTypedArray()
                val pendingIntent = args.getOrNull(0) as? PendingIntent
                val entry = args.getOrNull(2)
                val row = args.getOrNull(3)
                val alreadyZoom = args.getOrNull(ZOOM_ARG) as? Boolean
                if (pendingIntent == null || entry == null || row == null ||
                    !pendingIntent.isActivity || alreadyZoom != false
                ) return@intercept chain.proceed()

                val isBubble = HookSupport.invokeNoArgs(entry, "isBubble") as? Boolean
                    ?: return@intercept chain.proceed()
                if (!NotificationClickZoomPolicy.shouldCheckTarget(
                        isActivity = pendingIntent.isActivity,
                        isBubble = isBubble,
                        alreadyZoomPath = alreadyZoom,
                    )
                ) return@intercept chain.proceed()

                val rowExtension = HookSupport.getFieldValue(row, "mRowEx")
                    ?: return@intercept chain.proceed()
                val bundle = calculateBounds(rowExtension, row)
                    ?: return@intercept chain.proceed()
                val tapExitVelocityY = NotificationClickZoomPolicy.tapExitVelocityY(
                    isActivity = pendingIntent.isActivity,
                    isBubble = isBubble,
                    alreadyZoomPath = alreadyZoom,
                    resizeMode = bundle.getInt(RESIZE_MODE, 0),
                ) ?: return@intercept chain.proceed()

                if (!NotificationClickZoomPolicy.promoteLaunchArguments(args)) {
                    return@intercept chain.proceed()
                }
                hooks.logger.debug { "通知点击目标 ResizeMode=4，使用原生小窗参数: animate=${args[4]}, activityLaunch=${args[5]}, zoom=${args[6]}, 上滑速度=$tapExitVelocityY" }
                withTapExitVelocity(entry, tapExitVelocityY) {
                    chain.proceed(args)
                }
            }
        }
    }

    private inline fun <T> withTapExitVelocity(entry: Any, velocityY: Float, block: () -> T): T {
        val notification = HookSupport.invokeNoArgs(entry, "getSbn")
            ?.let { HookSupport.invokeNoArgs(it, "getNotification") }
        val extras = notification?.let { HookSupport.getFieldValue(it, "extras") as? Bundle }
            ?: return block()
        val hadPreviousValue = extras.containsKey(ZOOM_CURRENT_SPEED_Y)
        val previousValue = extras.getFloat(ZOOM_CURRENT_SPEED_Y)
        return try {
            extras.putFloat(ZOOM_CURRENT_SPEED_Y, velocityY)
            block()
        } finally {
            if (hadPreviousValue) {
                extras.putFloat(ZOOM_CURRENT_SPEED_Y, previousValue)
            } else {
                extras.remove(ZOOM_CURRENT_SPEED_Y)
            }
        }
    }

    private fun calculateBounds(rowExtension: Any, row: Any): Bundle? {
        val method: Method = HookSupport.findPublicMethod(rowExtension.javaClass) { candidate ->
            val parameters = candidate.parameterTypes
            candidate.name == "calculateFlexibleWindowBounds" &&
                Bundle::class.java.isAssignableFrom(candidate.returnType) &&
                parameters.size == 3 && parameters[0].isInstance(row) &&
                parameters[1] == Boolean::class.javaPrimitiveType &&
                parameters[2] == Boolean::class.javaPrimitiveType
        } ?: return null
        return try {
            method.invoke(rowExtension, row, false, true) as? Bundle
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

internal object NotificationClickZoomPolicy {
    private const val SUPPORTED_RESIZE_MODE = 4
    // Native onStartFloatWindow calls startNotificationIntent(..., true, isActivity, true).
    private const val FLOAT_WINDOW_ANIMATE_ARG = 4
    private const val ACTIVITY_LAUNCH_ARG = 5
    private const val ZOOM_ARG = 6
    // ColorOS switches its native zoom spring to the faster response above 6000 px/s.
    private const val TAP_EXIT_FLING_SPEED_Y = 6500f
    fun shouldCheckTarget(
        isActivity: Boolean,
        isBubble: Boolean,
        alreadyZoomPath: Boolean?,
    ): Boolean = isActivity && !isBubble && alreadyZoomPath == false

    fun shouldPromote(
        isActivity: Boolean,
        isBubble: Boolean,
        alreadyZoomPath: Boolean?,
        resizeMode: Int,
    ): Boolean =
        shouldCheckTarget(isActivity, isBubble, alreadyZoomPath) &&
            resizeMode == SUPPORTED_RESIZE_MODE

    fun promoteLaunchArguments(args: Array<Any?>): Boolean {
        if (args.size <= ZOOM_ARG ||
            args[FLOAT_WINDOW_ANIMATE_ARG] !is Boolean ||
            args[ACTIVITY_LAUNCH_ARG] !is Boolean ||
            args[ZOOM_ARG] !is Boolean
        ) return false
        args[FLOAT_WINDOW_ANIMATE_ARG] = true
        args[ACTIVITY_LAUNCH_ARG] = true
        args[ZOOM_ARG] = true
        return true
    }

    fun tapExitVelocityY(
        isActivity: Boolean,
        isBubble: Boolean,
        alreadyZoomPath: Boolean?,
        resizeMode: Int,
    ): Float? = if (shouldPromote(isActivity, isBubble, alreadyZoomPath, resizeMode)) {
        TAP_EXIT_FLING_SPEED_Y
    } else {
        null
    }
}
