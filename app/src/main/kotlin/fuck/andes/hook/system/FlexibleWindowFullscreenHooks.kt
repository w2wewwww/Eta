package fuck.andes.hook.system

import fuck.andes.core.HookInstallation
import fuck.andes.core.HookRegistrar
import fuck.andes.core.HookSupport
import fuck.andes.core.ModuleLogger
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * ColorOS 在 maxWinNum >= 2 时会让多种 Activity 启动入口复用 flexible task
 * 选项。对常规应用启动统一走 ROM 已定义的“force fullscreen”分支（reason = 3），
 * 但保留通知点击、通知回复和气泡通知的原生小窗路径。
 * Home\/Recents 与已有分屏任务不干预。
 */
internal object FlexibleWindowFullscreenHooks {
    private const val FLEXIBLE_TASK_CONTROLLER =
        "com.android.server.wm.FlexibleTaskController"
    private const val FLEXIBLE_WINDOW_UTILS =
        "com.android.server.wm.FlexibleWindowUtils"
    private const val ZOOM_WINDOW_UTILS =
        "com.android.server.wm.OplusZoomWindowUtil"
    private const val ACTIVITY_OPTIONS = "android.app.ActivityOptions"

    private const val ARG_OPTIONS = 0
    private const val ARG_START_ACTIVITY = 2
    private const val ARG_TARGET_TASK = 3
    private const val REASON_FORCE_FULLSCREEN = 3
    private const val NOTIFICATION_ZOOM_FLAG_TAP = 5
    private const val NOTIFICATION_ZOOM_FLAG_REPLY = 20
    private const val NOTIFICATION_ZOOM_FLAG_BUBBLE = 21

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "FlexibleWindowFullscreen")
        return hooks.install {
            val controllerClass = HookSupport.findClassOrNull(
                classLoader,
                FLEXIBLE_TASK_CONTROLLER,
            )
            val method = controllerClass?.let(::findIsAllowedToAdjustOptions)
            if (method == null) {
                hooks.missing(
                    id = "system.flexible-window-fullscreen",
                    description = "FlexibleTaskController.isAllowedToAdjustOptions",
                    detail = "未找到 ColorOS 16 的 8 参数 isAllowedToAdjustOptions(...)",
                )
                return@install
            }

            val resetZoomOptions = findResetZoomOptions(classLoader)
            val getZoomLaunchFlag = findGetZoomLaunchFlag(classLoader)
            if (resetZoomOptions == null || getZoomLaunchFlag == null) {
                hooks.missing(
                    id = "system.flexible-window-reset-options",
                    description = "FlexibleWindowUtils.resetZoomOptions",
                    detail = "未找到全屏回退所需的 resetZoomOptions/getZoomLaunchFlag(ActivityOptions)"
                )
                return@install
            }
            HookSupport.deoptimize(
                module,
                hooks.logger,
                method,
                "FlexibleTaskController.isAllowedToAdjustOptions",
            )
            hooks.intercept(
                id = "system.flexible-window-fullscreen",
                executable = method,
                description = "所有常规应用启动强制全屏",
            ) { chain ->
                if (!isRegularAppLaunch(chain)) return@intercept chain.proceed()

                val options = chain.getArg(ARG_OPTIONS)
                val zoomLaunchFlag = runCatching {
                    getZoomLaunchFlag.invoke(null, options) as? Number
                }.getOrNull()?.toInt()
                if (zoomLaunchFlag != null && isNotificationLaunch(zoomLaunchFlag)) {
                    return@intercept chain.proceed()
                }
                if (options != null) runCatching { resetZoomOptions.invoke(null, options) }
                REASON_FORCE_FULLSCREEN
            }
        }
    }

    private fun findIsAllowedToAdjustOptions(controllerClass: Class<*>): Method? =
        HookSupport.findDeclaredMethods(
            clazz = controllerClass,
            makeAccessible = true,
        ) { method ->
            method.name == "isAllowedToAdjustOptions" &&
                method.returnType == Int::class.javaPrimitiveType &&
                method.parameterTypes.size == 8 &&
                method.parameterTypes[4] == Int::class.javaPrimitiveType &&
                method.parameterTypes[5] == Boolean::class.javaPrimitiveType
        }.singleOrNull()

    private fun findResetZoomOptions(classLoader: ClassLoader): Method? {
        val utilsClass = HookSupport.findClassOrNull(classLoader, FLEXIBLE_WINDOW_UTILS) ?: return null
        val optionsClass = HookSupport.findClassOrNull(classLoader, ACTIVITY_OPTIONS) ?: return null
        return HookSupport.findMethod(utilsClass, "resetZoomOptions", optionsClass)
    }

    private fun findGetZoomLaunchFlag(classLoader: ClassLoader): Method? {
        val utilsClass = HookSupport.findClassOrNull(classLoader, ZOOM_WINDOW_UTILS) ?: return null
        val optionsClass = HookSupport.findClassOrNull(classLoader, ACTIVITY_OPTIONS) ?: return null
        return HookSupport.findMethod(utilsClass, "getZoomLaunchFlag", optionsClass)
    }

    private fun isNotificationLaunch(flag: Int): Boolean =
        flag == NOTIFICATION_ZOOM_FLAG_TAP ||
            flag == NOTIFICATION_ZOOM_FLAG_REPLY ||
            flag == NOTIFICATION_ZOOM_FLAG_BUBBLE

    /**
     * isAllowedToAdjustOptions is called after all launch origins have been normalized.
     * Exclude system navigation and an existing split task; every ordinary Activity start
     * receives the same fullscreen policy regardless of caller process or Zoom flags.
     */
    private fun isRegularAppLaunch(
        chain: io.github.libxposed.api.XposedInterface.Chain,
    ): Boolean {
        val startActivity = chain.getArg(ARG_START_ACTIVITY) ?: return false
        if (isHomeOrRecents(startActivity)) return false

        val targetTask = chain.getArg(ARG_TARGET_TASK)
        return targetTask == null || !isInMultiWindowMode(targetTask)
    }

    private fun isHomeOrRecents(activityRecord: Any): Boolean =
        (HookSupport.invokeNoArgs(activityRecord, "isActivityTypeHomeOrRecents") as? Boolean)
            ?: true

    private fun isInMultiWindowMode(task: Any): Boolean =
        (HookSupport.invokeNoArgs(task, "inMultiWindowMode") as? Boolean)
            ?: false

}
