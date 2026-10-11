package dev.pocortx.compat;

import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Iris replaces the whole world pipeline when a shader pack is active, so the
 * screen-space passes step aside in that case. Reflection keeps Iris optional.
 */
public final class IrisCompat {
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("iris");
    private static Object api;
    private static Method inUse;
    private static boolean broken;

    private IrisCompat() {
    }

    public static boolean isShaderPackInUse() {
        if (!LOADED || broken) {
            return false;
        }
        try {
            if (inUse == null) {
                Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = type.getMethod("getInstance").invoke(null);
                inUse = type.getMethod("isShaderPackInUse");
            }
            return (Boolean) inUse.invoke(api);
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            return false;
        }
    }
}
