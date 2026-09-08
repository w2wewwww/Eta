package fuck.andes.hook.system

import fuck.andes.core.HookInstallation
import fuck.andes.core.HookRegistrar
import fuck.andes.core.HookSupport
import fuck.andes.core.ModuleLogger
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * ColorOS 在 maxWinNum >= 2 时会将 Launcher 的普通启动带入 flexible task
 * 选项复用链路。仅拦截桌面图标发起、且并非显式浮窗的启动，复用 ROM 已定义的
 * "force fullscreen" 分支（reason = 3），不改动多浮窗数量和已存在浮窗的管理逻辑。
 */
internal object FlexibleWindowFullscreenHooks {
    private const val FLEXIBLE_TASK_CONTROLLER =
        "com.android.server.wm.FlexibleTaskController"
    private const val FLEXIBLE_WINDOW_UTILS =
        "com.android.server.wm.FlexibleWindowUtils"
    private const val ACTIVITY_OPTIONS = "android.app.ActivityOptions"

    private const val ARG_OPTIONS = 0
    private const val ARG_START_ACTIVITY = 2
    private const val ARG_LAUNCH_FROM_ZOOM = 5
    private const val ARG_REQUEST = 7

    private const val LAUNCH_SOURCE_LAUNCHER = 2
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
                    id = "system.flexible-window-launcher-fullscreen",
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
                id = "system.flexible-window-launcher-fullscreen",
                executable = method,
                description = "Launcher 普通启动强制全屏",
            ) { chain ->
                if (!isPlainLauncherLaunch(chain.getThisObject(), chain)) {
                    return@intercept chain.proceed()
                }
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

    /** Mirrors FlexibleTaskController.isStartFromLauncher on the inspected ColorOS build. */
    private fun isPlainLauncherLaunch(
        controller: Any?,
        chain: io.github.libxposed.api.XposedInterface.Chain,
    ): Boolean {
        if (controller == null || chain.getArg(ARG_LAUNCH_FROM_ZOOM) == true) return false
        val startActivity = chain.getArg(ARG_START_ACTIVITY) ?: return false
        if (!isLauncherSourceType(startActivity)) return false

        val request = chain.getArg(ARG_REQUEST) ?: return false
        val realCallingPid = HookSupport.getFieldValue(request, "realCallingPid") as? Int
            ?: return false
        val atms = HookSupport.getFieldValue(controller, "mAtms") ?: return false
        val homeProcess = HookSupport.getFieldValue(atms, "mHomeProcess") ?: return false
        val homePid = HookSupport.getFieldValue(homeProcess, "mPid") as? Int ?: return false
        return homePid > 0 && homePid == realCallingPid
    }

    private fun isLauncherSourceType(activityRecord: Any): Boolean = runCatching {
        val method = HookSupport.findMethod(
            activityRecord.javaClass,
            "isLaunchSourceType",
            Int::class.javaPrimitiveType!!,
        ) ?: return@runCatching false
        method.invoke(activityRecord, LAUNCH_SOURCE_LAUNCHER) as? Boolean ?: false
    }.getOrDefault(false)
}
