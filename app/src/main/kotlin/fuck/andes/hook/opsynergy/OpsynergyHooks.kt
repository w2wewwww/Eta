package fuck.andes.hook.opsynergy

import fuck.andes.core.HookInstallation
import fuck.andes.core.HookRegistrar
import fuck.andes.core.HookSupport
import fuck.andes.core.ModuleConfig
import fuck.andes.core.ModuleLogger
import io.github.libxposed.api.XposedModule

/** Bypass the phone-side PC Connect mirroring package allow-list. */
internal object OpsynergyHooks {
    private const val FIND_PHONE_APPLICATION =
        "com.oplus.linker.mac.client.data.source.FindPhoneApplication"
    private const val WHITE_LIST_PREDICATE = "getAllWhiteApps\$lambda\$6\$lambda\$2"

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "OPSynergy")
        return hooks.install {
            val owner = HookSupport.findClassOrNull(classLoader, FIND_PHONE_APPLICATION)
            if (owner == null) {
                hooks.missing(
                    id = "opsynergy.mirroring-all-apps",
                    description = "FindPhoneApplication whitelist predicate",
                    detail = "未找到 OPSynergy 白名单类"
                )
                return@install
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
                return@install
            }
            hooks.intercept(
                id = "opsynergy.mirroring-all-apps",
                executable = predicate,
                description = "允许所有已安装应用通过 PC Connect 投屏白名单"
            ) { true }
        }
    }
}
