package fuck.andes.hook.opsynergy

import android.content.Context
import android.content.Intent
import fuck.andes.core.HookInstallation
import fuck.andes.core.HookRegistrar
import fuck.andes.core.HookSupport
import fuck.andes.core.ModuleLogger
import io.github.libxposed.api.XposedModule

/** Bypass enumeration filtering and admit launcher apps to Mirage. */
internal object OpsynergyHooks {
    private const val FIND_PHONE_APPLICATION =
        "com.oplus.linker.mac.client.data.source.FindPhoneApplication"
    private const val WHITE_LIST_PREDICATE = "getAllWhiteApps\$lambda\$6\$lambda\$2"
    private const val CAST_RUS_DATA_MANAGER =
        "com.oplus.linker.pc.cast.rus.CastRUSDataManager"
    private const val CAST_MESSAGE_MANAGER =
        "com.oplus.linker.pc.cast.scene.CastMessageManager"
    private const val START_MIRAGE_COROUTINE =
        "com.oplus.linker.pc.cast.scene.MirageSceneImpl\$startMirageCast\$1"

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "OPSynergy")
        return hooks.install {
            installAppEnumeration(hooks, classLoader)
            installMirageLaunchPolicy(module, hooks, classLoader)
        }
    }

    private fun installAppEnumeration(hooks: HookRegistrar, classLoader: ClassLoader) {
        val owner = HookSupport.findClassOrNull(classLoader, FIND_PHONE_APPLICATION)
        if (owner == null) {
            hooks.missing(
                id = "opsynergy.mirroring-all-apps",
                description = "FindPhoneApplication whitelist predicate",
                detail = "未找到 OPSynergy 白名单类"
            )
            return
        }
        val predicate = HookSupport.findDeclaredMethods(owner, makeAccessible = true) { method ->
            method.name == WHITE_LIST_PREDICATE &&
                method.parameterTypes.size == 2 &&
                method.returnType == Boolean::class.javaPrimitiveType
        }.singleOrNull()
        if (predicate == null) {
            hooks.missing(
                id = "opsynergy.mirroring-all-apps",
                description = "FindPhoneApplication whitelist predicate",
                detail = "未找到唯一的白名单判断方法；ROM 版本可能已变更"
            )
            return
        }
        hooks.intercept(
            id = "opsynergy.mirroring-all-apps",
            executable = predicate,
            description = "允许所有已安装应用通过 PC Connect 投屏白名单"
        ) { true }
    }

    private fun installMirageLaunchPolicy(
        module: XposedModule,
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val owner = HookSupport.findClassOrNull(classLoader, CAST_RUS_DATA_MANAGER)
        val getter = owner?.let { HookSupport.findMethod(it, "getMirageAppList") }
            ?.takeIf { it.returnType == List::class.java }
        if (getter == null) {
            hooks.missing(
                id = "opsynergy.mirage-launch-apps",
                description = "CastRUSDataManager.getMirageAppList",
                detail = "Mirage launch allow-list getter was not found"
            )
            return
        }
        val currentApplication = HookSupport.findClassOrNull(classLoader, "android.app.ActivityThread")
            ?.let { HookSupport.findMethod(it, "currentApplication") }
        if (currentApplication == null) {
            hooks.missing(
                id = "opsynergy.mirage-launch-context",
                description = "ActivityThread.currentApplication",
                detail = "Application context accessor was not found; keeping the stock launch policy"
            )
            return
        }
        val handle = hooks.intercept(
            id = "opsynergy.mirage-launch-apps",
            executable = getter,
            description = "Expand the Mirage launch allow-list with enabled launcher apps"
        ) { chain ->
            val original = chain.proceed()
            if (original !is List<*>) return@intercept original
            try {
                // Resolve on each call so startup, package changes and RUS updates cannot stale the list.
                val context = currentApplication.invoke(null) as? Context
                if (context == null) {
                    hooks.logger.warnThrottled("mirage.context-unavailable") {
                        "Application context is not ready; keeping the stock Mirage allow-list"
                    }
                    original
                } else {
                    expandMirageAppList(context, original)
                }
            } catch (exception: Exception) {
                hooks.logger.warnThrottled("mirage.launcher-query-failed") {
                    "Mirage launcher query failed: ${exception.javaClass.simpleName}; keeping the stock list"
                }
                original
            }
        }
        if (handle == null) return

        // These callers can inline the tiny getter and keep reading the unmodified RUS field.
        for ((className, methodName) in listOf(
            CAST_MESSAGE_MANAGER to "sendCastParameters",
            START_MIRAGE_COROUTINE to "invokeSuspend"
        )) {
            val caller = HookSupport.findClassOrNull(classLoader, className)
            val method = caller?.let { clazz ->
                HookSupport.findDeclaredMethods(clazz, makeAccessible = true) {
                    it.name == methodName && it.parameterTypes.size == 1
                }.singleOrNull()
            }
            if (method == null) {
                hooks.missing(
                    id = "opsynergy.mirage-deopt-${methodName.lowercase()}",
                    description = "$className.$methodName",
                    detail = "Mirage caller was not found for deoptimization: $className.$methodName"
                )
            } else {
                HookSupport.deoptimize(module, hooks.logger, method, "$className.$methodName")
            }
        }
    }

    private fun expandMirageAppList(context: Context, original: List<*>): List<*> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = context.packageManager.queryIntentActivities(launcherIntent, 0)
        val packages = LinkedHashSet<Any?>(original)
        for (resolveInfo in activities) {
            val activity = resolveInfo.activityInfo ?: continue
            if (activity.enabled && activity.exported && activity.applicationInfo?.enabled == true &&
                !activity.packageName.isNullOrBlank()
            ) {
                packages.add(activity.packageName)
            }
        }
        return ArrayList(packages)
    }
}
