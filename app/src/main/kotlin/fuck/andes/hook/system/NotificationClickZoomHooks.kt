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
import java.util.concurrent.atomic.AtomicBoolean

/** Route eligible notification taps through the same native float-window entry used by the
 * notification menu. Unsupported targets stay on the normal fullscreen path. */
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

                val starter = chain.getThisObject()
                val nativeStarter = HookSupport.getFieldValue(starter, "mActivityStarterEx")
                if (!NotificationClickZoomPolicy.promoteLaunchArguments(args)) {
                    return@intercept chain.proceed()
                }
                val shadeController = HookSupport.getFieldValue(starter, "mShadeController")
                val panelExpanded = shadeController?.let {
                    HookSupport.invokeNoArgs(it, "isExpandedVisible") as? Boolean
                } ?: false
                val isHeadsUp = HookSupport.invokeNoArgs(row, "isHeadsUpState") as? Boolean ?: true
                val keyguard = HookSupport.getFieldValue(starter, "mKeyguardStateController")
                val keyguardShowing = keyguard?.let {
                    HookSupport.getFieldValue(it, "mShowing") as? Boolean
                } ?: true
                val rowOnKeyguard = HookSupport.getFieldValue(row, "mOnKeyguard") as? Boolean ?: true
                if (NotificationClickZoomPolicy.shouldDeferUntilShadeCollapsed(
                        panelExpanded = panelExpanded,
                        isHeadsUp = isHeadsUp,
                        isOnKeyguard = keyguardShowing || rowOnKeyguard,
                    ) && shadeController != null && deferUntilShadeCollapsed(
                        module = module,
                        method = method,
                        starter = starter,
                        shadeController = shadeController,
                        nativeStarter = nativeStarter,
                        row = row,
                        args = args,
                        entry = entry,
                        velocityY = tapExitVelocityY,
                        logger = hooks.logger,
                    )
                ) {
                    hooks.logger.debug { "通知中心先收起，再启动小窗" }
                    return@intercept null
                }

                if (nativeStarter != null && invokeNativeFloatWindow(
                        nativeStarter, starter, row, tapExitVelocityY, hooks.logger,
                    )
                ) {
                    hooks.logger.debug { "通知点击复用长按菜单原生浮窗入口，保留打开动画" }
                    return@intercept null
                }
                hooks.logger.debug { "通知点击目标 ResizeMode=4，使用原生小窗参数并保留上滑速度=$tapExitVelocityY" }
                withTapExitVelocity(entry, tapExitVelocityY) {
                    chain.proceed(args)
                }
            }
        }
    }

    private fun invokeNativeFloatWindow(
        nativeStarter: Any,
        starter: Any,
        row: Any,
        velocityY: Float,
        logger: ModuleLogger,
    ): Boolean {
        val method = HookSupport.findPublicMethod(nativeStarter.javaClass) { candidate ->
            val parameters = candidate.parameterTypes
            candidate.name == "onStartFloatWindow" &&
                candidate.returnType == Boolean::class.javaPrimitiveType &&
                parameters.size == 3 && parameters[0].isInstance(starter) &&
                parameters[1].isInstance(row) && parameters[2] == Float::class.javaPrimitiveType
        } ?: return false
        return try {
            method.invoke(nativeStarter, starter, row, velocityY) as? Boolean == true
        } catch (exception: ReflectiveOperationException) {
            logger.error("调用长按菜单原生浮窗入口失败", exception)
            false
        } catch (exception: IllegalArgumentException) {
            logger.error("原生浮窗入口参数不匹配", exception)
            false
        }
    }

    private fun deferUntilShadeCollapsed(
        module: XposedModule,
        method: Method,
        starter: Any,
        shadeController: Any,
        nativeStarter: Any?,
        row: Any,
        args: Array<Any?>,
        entry: Any,
        velocityY: Float,
        logger: ModuleLogger,
    ): Boolean {
        val addPostCollapseAction = HookSupport.findMethod(
            shadeController.javaClass,
            "addPostCollapseAction",
            Runnable::class.java,
        ) ?: return false
        val collapseShade = HookSupport.findMethod(
            shadeController.javaClass,
            "collapseShade",
            Boolean::class.javaPrimitiveType!!,
        ) ?: return false
        val invoker = try {
            module.getInvoker(method)
        } catch (exception: Exception) {
            logger.error("无法创建通知启动原方法调用器", exception)
            return false
        }
        val launched = AtomicBoolean(false)
        val launchAfterCollapse = Runnable {
            if (!launched.compareAndSet(false, true)) return@Runnable
            try {
                if (nativeStarter != null && invokeNativeFloatWindow(
                        nativeStarter, starter, row, velocityY, logger,
                    )
                ) {
                    logger.debug { "通知中心收起后已调用原生浮窗入口" }
                } else {
                    withTapExitVelocity(entry, velocityY) {
                        invoker.invoke(starter, *args)
                    }
                }
            } catch (exception: Exception) {
                logger.error("通知中心收起后启动小窗失败", exception)
            }
        }
        return try {
            addPostCollapseAction.invoke(shadeController, launchAfterCollapse)
            collapseShade.invoke(shadeController, true)
            true
        } catch (exception: ReflectiveOperationException) {
            logger.error("无法启动通知中心收起动画", exception)
            launchAfterCollapse.run()
            true
        } catch (exception: IllegalArgumentException) {
            logger.error("通知中心收起动画参数不匹配", exception)
            launchAfterCollapse.run()
            true
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

    fun shouldDeferUntilShadeCollapsed(
        panelExpanded: Boolean,
        isHeadsUp: Boolean,
        isOnKeyguard: Boolean,
    ): Boolean = panelExpanded && !isHeadsUp && !isOnKeyguard

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
