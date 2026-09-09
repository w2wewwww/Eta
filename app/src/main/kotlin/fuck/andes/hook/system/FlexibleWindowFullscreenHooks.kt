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
 * 避免桌面、通知、最近任务、分享和应用内跳转分别落入不同的小窗路径。
 * Home\/Recents 与已有分屏任务不干预。
 */
internal object FlexibleWindowFullscreenHooks {
    private const val FLEXIBLE_TASK_CONTROLLER =
        "com.android.server.wm.FlexibleTaskController"
    private const val FLEXIBLE_WINDOW_UTILS =
        "com.android.server.wm.FlexibleWindowUtils"
    private const val ACTIVITY_OPTIONS = "android.app.ActivityOptions"

    private const val ARG_OPTIONS = 0
    private const val ARG_START_ACTIVITY = 2
    private const val ARG_TARGET_TASK = 3
    private const val REASON_FORCE_FULLSCREEN = 3

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
            if (resetZoomOptions == null) {
                hooks.missing(
                    id = "system.flexible-window-reset-options",
                    description = "FlexibleWindowUtils.resetZoomOptions",
                    detail = "未找到全屏回退所需的 resetZoomOptions(ActivityOptions)"
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
