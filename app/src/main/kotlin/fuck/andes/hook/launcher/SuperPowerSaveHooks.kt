package fuck.andes.hook.launcher

import android.content.ComponentName
import android.content.Context
import android.os.UserHandle
import fuck.andes.core.HookInstallation
import fuck.andes.core.HookRegistrar
import fuck.andes.core.HookSupport
import fuck.andes.core.ModuleLogger
import io.github.libxposed.api.XposedModule
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method

/** Extends the stock 3 x 2 Super Power Save grid to 3 x 4. */
internal object SuperPowerSaveHooks {
    private const val APPS_MANAGER = "com.android.launcher.powersave.SuperPowerSaveAppsManager"
    private const val MODEL_WRITER = "com.android.launcher.powersave.SuperPowerSaveModelWriter"
    private const val MODE_MANAGER = "com.android.launcher.powersave.SuperPowerModeManager"
    private const val COLUMNS = 3
    private const val STOCK_COUNT = 6
    private const val MAX_COUNT = 12

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "SuperPowerSave")
        return hooks.install {
            installWorkspaceBuilder(hooks, classLoader)
            installWorkspaceReader(hooks, classLoader)
            installListSerializer(hooks, classLoader)
        }
    }

    private fun installWorkspaceBuilder(hooks: HookRegistrar, classLoader: ClassLoader) {
        val managerClass = HookSupport.findClassOrNull(classLoader, APPS_MANAGER)
        val method = managerClass?.let {
            HookSupport.findDeclaredMethods(it, true) { candidate ->
                candidate.name == "buildWorkSpaceAppList" &&
                    candidate.parameterTypes.size == 2 && candidate.returnType == Void.TYPE
            }.singleOrNull()
        }
        if (method == null) {
            hooks.missing("launcher.super-power-workspace", "buildWorkSpaceAppList", "未找到超级省电桌面构建方法")
            return
        }
        hooks.intercept(
            id = "launcher.super-power-workspace",
            executable = method,
            description = "将超级省电桌面扩展为 12 个图标槽位",
        ) { chain ->
            val result = chain.proceed()
            runCatching {
                appendSlots(chain.getThisObject(), chain.getArg(0), chain.getArg(1), classLoader)
            }.onFailure { error ->
                hooks.logger.warnThrottled("super-power.append") {
                    "扩展超级省电槽位失败: ${error.javaClass.simpleName}"
                }
            }
            result
        }
    }

    private fun installWorkspaceReader(hooks: HookRegistrar, classLoader: ClassLoader) {
        val managerClass = HookSupport.findClassOrNull(classLoader, APPS_MANAGER)
        val method = managerClass?.let {
            HookSupport.findDeclaredMethods(it, true) { candidate ->
                candidate.name == "initWorkSpaceAppList" && candidate.parameterTypes.isEmpty() &&
                    java.util.List::class.java.isAssignableFrom(candidate.returnType)
            }.singleOrNull()
        }
        if (method == null) {
            hooks.missing("launcher.super-power-restore", "initWorkSpaceAppList", "未找到超级省电桌面读取方法")
            return
        }
        hooks.intercept(
            id = "launcher.super-power-restore",
            executable = method,
            description = "恢复额外超级省电桌面槽位",
        ) { chain ->
            val result = chain.proceed()
            runCatching { restoreSlots(result, chain.getThisObject(), classLoader) }
                .onFailure { error ->
                    hooks.logger.warnThrottled("super-power.restore") {
                        "恢复超级省电槽位失败: ${error.javaClass.simpleName}"
                    }
                }
            result
        }
    }

    private fun installListSerializer(hooks: HookRegistrar, classLoader: ClassLoader) {
        val managerClass = HookSupport.findClassOrNull(classLoader, MODE_MANAGER)
        val method = managerClass?.let {
            HookSupport.findDeclaredMethods(it, true) { candidate ->
                candidate.name == "toJsonInfos" && candidate.parameterTypes.size == 1 &&
                    candidate.returnType == String::class.java
            }.singleOrNull()
        }
        if (method == null) {
            hooks.missing("launcher.super-power-desktop-list", "toJsonInfos", "未找到超级省电列表序列化方法")
            return
        }
        hooks.intercept(
            id = "launcher.super-power-desktop-list",
            executable = method,
            description = "保存全部超级省电桌面图标",
        ) { chain ->
            val items = chain.getArg(0) as? Iterable<*> ?: return@intercept chain.proceed()
            serializeDesktopApps(items)
        }
    }

    private fun appendSlots(owner: Any?, apps: Any?, workspace: Any?, classLoader: ClassLoader) {
        val manager = owner ?: return
        val items = workspace as? MutableList<Any?> ?: return
        if (items.size >= MAX_COUNT) return
        val allApps = apps as? ArrayList<*> ?: return
        val context = HookSupport.getFieldValue(manager, "mContext") as? Context ?: return
        val getItem = findItemFromPrefs(classLoader) ?: return
        val findMatch = findModelMatch(manager.javaClass) ?: return
        val buildTip = HookSupport.findMethod(
            manager.javaClass,
            "buildTipIconItem",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        ) ?: return

        for (position in items.size until MAX_COUNT) {
            val x = position % COLUMNS
            val y = position / COLUMNS
            val model = getItem.invoke(null, context, x, y)
            val item = if (model == null) buildTip.invoke(manager, x, y)
            else findMatch.invoke(manager, model, allApps) ?: buildTip.invoke(manager, x, y)
            items += item
        }
    }

    private fun restoreSlots(models: Any?, owner: Any?, classLoader: ClassLoader) {
        val items = models as? MutableList<Any?> ?: return
        if (items.size >= MAX_COUNT) return
        val manager = owner ?: return
        val context = HookSupport.getFieldValue(manager, "mContext") as? Context ?: return
        val getItem = findItemFromPrefs(classLoader) ?: return
        for (position in STOCK_COUNT until MAX_COUNT) {
            getItem.invoke(null, context, position % COLUMNS, position / COLUMNS)?.let { items += it }
        }
    }

    private fun serializeDesktopApps(items: Iterable<*>): String {
        val output = JSONArray()
        for (item in items) {
            val component = item?.let(::targetComponent) ?: continue
            val user = item.let { HookSupport.getFieldValue(it, "user") as? UserHandle } ?: continue
            output.put(JSONObject().put("packageName", component.packageName).put("user", user.identifier))
        }
        return output.toString()
    }

    private fun targetComponent(item: Any): ComponentName? = runCatching {
        HookSupport.findMethod(item.javaClass, "getMTargetComponent")?.invoke(item) as? ComponentName
    }.getOrNull()

    private fun findItemFromPrefs(classLoader: ClassLoader): Method? =
        HookSupport.findClassOrNull(classLoader, MODEL_WRITER)?.let {
            HookSupport.findMethod(
                it,
                "getItemFromSp",
                Context::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
        }

    private fun findModelMatch(managerClass: Class<*>): Method? =
        HookSupport.findDeclaredMethods(managerClass, true) {
            it.name == "findMatchItem" && it.parameterTypes.size == 2
        }.singleOrNull()
}
