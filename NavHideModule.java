package com.hidenavbar.navhide;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

import java.lang.reflect.Method;

/**
 * NavHide module hooks.
 *
 * H3: Keep the selected IME's bottom navigation-button dimension at zero.
 * H6: Keep navigation-bar InsetsSource frames empty for navigation-bar immersion.
 * H7: Skip drawing NavigationHandle while the device is in landscape. The View
 *     and its input dispatch remain untouched, so long-press/recognition handling
 *     is not disabled by this hook.
 * H8: At the WindowManager boundary, make ordinary application windows fit the
 *     visible status-bar and display-cutout insets. This is global because the
 *     hook runs in system_server; it does not require hooking every app process.
 */
public class NavHideModule extends XposedModule {
    private static final String TAG = "NavHide";

    // InsetsSource type masks. These values match WindowInsets.Type constants.
    private static final int TYPE_NAVIGATION_BARS = 1 << 1;

    // Application window types. Sub-windows, starting windows and system windows
    // are deliberately excluded from the H8 compatibility adjustment.
    private static final int TYPE_BASE_APPLICATION = 1;
    private static final int TYPE_APPLICATION = 2;

    private static final int H8_SAFE_SIDES =
            WindowInsets.Side.TOP
                    | WindowInsets.Side.LEFT
                    | WindowInsets.Side.RIGHT;

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        Log.i(TAG, "[ENTRY] onModuleLoaded process=" + param.getProcessName());
    }

    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        Log.i(TAG, "[ENTRY] onHotReloading");
        return true;
    }

    @Override
    public void onHotReloaded(@NonNull HotReloadedParam param) {
        String processName = param.getProcessName();
        Log.i(TAG, "[ENTRY] onHotReloaded process=" + processName);

        if ("system_server".equals(processName)) {
            // In system_server, platform classes are visible to the default loader.
            installSystemServerHooks(null);
        } else if ("com.bytedance.android.doubaoime".equals(processName)) {
            installH3Ime();
        } else if ("com.android.systemui".equals(processName)) {
            // HotReloadedParam does not expose the package ClassLoader. The thread
            // context loader is the best available reload-time candidate.
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader != null) {
                installH7SystemUi(loader);
            } else {
                Log.w(TAG, "[H7] no SystemUI ClassLoader during hot reload; restart SystemUI");
            }
        }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        Log.i(TAG, "[ENTRY] onSystemServerStarting");
        installSystemServerHooks(param.getClassLoader());
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        String packageName = param.getPackageName();
        Log.i(TAG, "[ENTRY] onPackageLoaded package=" + packageName);

        if ("com.android.systemui".equals(packageName)) {
            installH7SystemUi(param.getDefaultClassLoader());
        } else if ("com.bytedance.android.doubaoime".equals(packageName)) {
            installH3Ime();
        }
    }

    private void installSystemServerHooks(ClassLoader classLoader) {
        Log.i(TAG, "[SYSTEM] installing system_server hooks");
        installH6NavigationInsets(classLoader);
        installH8WindowInsetsCompatibility(classLoader);
    }

    // -------------------------------------------------------------------------
    // H3: IME process
    // -------------------------------------------------------------------------

    private void installH3Ime() {
        Log.i(TAG, "[H3] installH3Ime start");
        try {
            Method getDimensionPixelSize = Resources.class.getDeclaredMethod(
                    "getDimensionPixelSize", int.class);

            hook(getDimensionPixelSize)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        int resourceId = (int) chain.getArg(0);
                        try {
                            Resources resources = (Resources) chain.getThisObject();
                            String resourceName = resources.getResourceEntryName(resourceId);
                            if ("input_method_navigation_bar_height".equals(resourceName)
                                    || "input_method_nav_buttons_height".equals(resourceName)) {
                                Log.i(TAG, "[H3] " + resourceName + " -> 0");
                                return 0;
                            }
                        } catch (Throwable ignored) {
                            // The resource may not belong to this IME. Use the original result.
                        }
                        return chain.proceed();
                    });

            Log.i(TAG, "[H3] Resources.getDimensionPixelSize hook installed");
        } catch (Throwable throwable) {
            Log.e(TAG, "[H3] hook installation failed", throwable);
        }
    }

    // -------------------------------------------------------------------------
    // H6: system_server navigation-bar Insets
    // -------------------------------------------------------------------------

    private void installH6NavigationInsets(ClassLoader classLoader) {
        Log.i(TAG, "[H6] installH6NavigationInsets start");
        try {
            Class<?> insetsSourceClass = Class.forName(
                    "android.view.InsetsSource",
                    false,
                    classLoader != null ? classLoader : ClassLoader.getSystemClassLoader());

            Method getType = insetsSourceClass.getDeclaredMethod("getType");
            Method setFrame = insetsSourceClass.getDeclaredMethod("setFrame", Rect.class);
            getType.setAccessible(true);
            setFrame.setAccessible(true);

            hook(setFrame)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            int type = (int) getType.invoke(chain.getThisObject());
                            Rect originalFrame = (Rect) chain.getArg(0);

                            if (type == TYPE_NAVIGATION_BARS
                                    && originalFrame != null
                                    && !originalFrame.isEmpty()) {
                                Rect emptyFrame = new Rect();
                                Log.d(TAG, "[H6] navigation-bar frame "
                                        + originalFrame.toShortString() + " -> EMPTY");
                                return chain.proceedWith(
                                        chain.getThisObject(),
                                        new Object[]{emptyFrame});
                            }
                        } catch (Throwable throwable) {
                            Log.e(TAG, "[H6] setFrame interception failed", throwable);
                        }
                        return chain.proceed();
                    });

            Log.i(TAG, "[H6] InsetsSource.setFrame hook installed");
        } catch (Throwable throwable) {
            Log.e(TAG, "[H6] InsetsSource hook installation failed", throwable);
        }

        // Preserve the existing IME navigation-button behavior.
        try {
            Method getBoolean = Resources.class.getDeclaredMethod("getBoolean", int.class);
            hook(getBoolean)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        int resourceId = (int) chain.getArg(0);
                        try {
                            Resources resources = (Resources) chain.getThisObject();
                            String resourceName = resources.getResourceName(resourceId);
                            if (resourceName != null
                                    && resourceName.endsWith("/config_imeDrawsImeNavBar")) {
                                Log.i(TAG, "[H6] " + resourceName + " -> false");
                                return false;
                            }
                        } catch (Throwable ignored) {
                            // Keep the framework's original resource behavior.
                        }
                        return chain.proceed();
                    });
            Log.i(TAG, "[H6] config_imeDrawsImeNavBar hook installed");
        } catch (Throwable throwable) {
            Log.e(TAG, "[H6] getBoolean hook installation failed", throwable);
        }
    }

    // -------------------------------------------------------------------------
    // H7: SystemUI — hide only the gesture-handle drawing in landscape
    // -------------------------------------------------------------------------

    private void installH7SystemUi(ClassLoader classLoader) {
        if (classLoader == null) {
            Log.e(TAG, "[H7] SystemUI ClassLoader is null");
            return;
        }

        Log.i(TAG, "[H7] installH7SystemUi start");
        try {
            Class<?> navigationHandleClass = Class.forName(
                    "com.android.systemui.navigationbar.gestural.NavigationHandle",
                    false,
                    classLoader);

            Method onDraw = navigationHandleClass.getDeclaredMethod("onDraw", Canvas.class);
            onDraw.setAccessible(true);

            hook(onDraw)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            View handleView = (View) chain.getThisObject();
                            int orientation = handleView.getResources()
                                    .getConfiguration().orientation;

                            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                                // Skip pixels only. Do not change visibility, alpha,
                                // focusability, touch dispatch, or gesture callbacks.
                                return null;
                            }
                        } catch (Throwable throwable) {
                            Log.e(TAG, "[H7] orientation check failed; drawing normally",
                                    throwable);
                        }

                        return chain.proceed();
                    });

            Log.i(TAG, "[H7] NavigationHandle.onDraw hook installed");
        } catch (Throwable throwable) {
            Log.e(TAG, "[H7] NavigationHandle hook installation failed", throwable);
        }
    }

    // -------------------------------------------------------------------------
    // H8: system_server — global top safe-area compatibility for app windows
    // -------------------------------------------------------------------------

    private void installH8WindowInsetsCompatibility(ClassLoader classLoader) {
        Log.i(TAG, "[H8] installH8WindowInsetsCompatibility start");
        try {
            Class<?> windowManagerServiceClass = Class.forName(
                    "com.android.server.wm.WindowManagerService",
                    false,
                    classLoader != null ? classLoader : ClassLoader.getSystemClassLoader());

            int hookedMethods = 0;
            for (Method method : windowManagerServiceClass.getDeclaredMethods()) {
                String methodName = method.getName();
                if (!"addWindow".equals(methodName)
                        && !"relayoutWindow".equals(methodName)) {
                    continue;
                }

                Class<?>[] parameterTypes = method.getParameterTypes();
                for (int index = 0; index < parameterTypes.length; index++) {
                    if (parameterTypes[index] != WindowManager.LayoutParams.class) {
                        continue;
                    }

                    final int layoutParamsIndex = index;
                    method.setAccessible(true);
                    hook(method)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                try {
                                    Object argument = chain.getArg(layoutParamsIndex);
                                    if (argument instanceof WindowManager.LayoutParams) {
                                        applyTopSafeInsets((WindowManager.LayoutParams) argument);
                                    }
                                } catch (Throwable throwable) {
                                    Log.e(TAG, "[H8] " + methodName
                                            + " parameter normalization failed", throwable);
                                }
                                return chain.proceed();
                            });

                    hookedMethods++;
                    break;
                }
            }

            if (hookedMethods == 0) {
                Log.e(TAG, "[H8] no addWindow/relayoutWindow LayoutParams overload found");
            } else {
                Log.i(TAG, "[H8] hooked " + hookedMethods
                        + " WindowManagerService entry point(s)");
            }
        } catch (Throwable throwable) {
            Log.e(TAG, "[H8] WindowManagerService hook installation failed", throwable);
        }
    }

    /**
     * Forces ordinary app windows to avoid the visible status bar and display cutout.
     * Navigation-bar fitting is intentionally not added, because H6 owns the module's
     * bottom/navigation behavior. Hidden/fullscreen and no-limits windows are preserved.
     */
    private void applyTopSafeInsets(WindowManager.LayoutParams attributes) {
        if (attributes == null) {
            return;
        }

        int windowType = attributes.type;
        if (windowType != TYPE_BASE_APPLICATION && windowType != TYPE_APPLICATION) {
            return;
        }

        int flags = attributes.flags;
        if ((flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0
                || (flags & WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS) != 0) {
            return;
        }

        // Respect apps that still request the legacy explicit fullscreen flag.
        if ((attributes.systemUiVisibility & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0) {
            return;
        }

        int requiredTypes = WindowInsets.Type.statusBars()
                | WindowInsets.Type.displayCutout();
        int oldTypes = attributes.getFitInsetsTypes();
        int newTypes = oldTypes | requiredTypes;

        int oldSides = attributes.getFitInsetsSides();
        int newSides = oldSides | H8_SAFE_SIDES;

        boolean changed = false;
        if (newTypes != oldTypes) {
            attributes.setFitInsetsTypes(newTypes);
            changed = true;
        }
        if (newSides != oldSides) {
            attributes.setFitInsetsSides(newSides);
            changed = true;
        }

        if (changed) {
            Log.i(TAG, "[H8] normalized app window type=" + windowType
                    + " fitTypes=0x" + Integer.toHexString(newTypes)
                    + " fitSides=0x" + Integer.toHexString(newSides));
        }
    }
}
