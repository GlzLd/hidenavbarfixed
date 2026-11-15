package com.hidenavbar.navhide;

import android.graphics.Rect;
import android.util.Log;
import android.content.res.Configuration;
import android.graphics.Canvas;

import androidx.annotation.NonNull;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public class NavHideModule extends XposedModule {
    private static final String TAG = "NavHide";

    // WindowInsets.Type constants
    private static final int TYPE_STATUS_BARS = 1;
    private static final int TYPE_IME = 8;
    private static final int TYPE_SYSTEM_GESTURES = 16;
    private static final int TYPE_MANDATORY_SYSTEM_GESTURES = 32;
    private static final int TYPE_TAPPABLE_ELEMENT = 64;
    

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
public void onPackageLoaded(@NonNull PackageLoadedParam param) {
    String pkg = param.getPackageName();

    Log.i(TAG, "[ENTRY] onPackageLoaded pkg=" + pkg);

    if ("com.android.systemui".equals(pkg)) {
        Log.i(TAG, "[DISPATCH] -> installH7_SystemUI");
        installH7_SystemUI(param.getDefaultClassLoader());

    } else if ("com.bytedance.android.doubaoime".equals(pkg)) {
        Log.i(TAG, "[DISPATCH] -> installH3_IME");
        installH3_IME();

    } else {
        Log.i(TAG, "[DISPATCH] package ignored: " + pkg);
    }
}

// ========== H7: SystemUI — 横屏隐藏手势条绘制 ==========

private void installH7_SystemUI(ClassLoader classLoader) {
    Log.i(TAG, "[H7] installH7_SystemUI start");

    try {
        Class<?> handleClass = Class.forName(
                "com.android.systemui.navigationbar.gestural.NavigationHandle",
                false,
                classLoader
        );

        java.lang.reflect.Method onDraw =
                handleClass.getDeclaredMethod("onDraw", Canvas.class);

        onDraw.setAccessible(true);

        hook(onDraw)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    try {
                        Object target = chain.getThisObject();

                        android.view.View view =
                                (android.view.View) target;

                        int orientation = view.getResources()
                                .getConfiguration().orientation;

                        if (orientation ==
                                Configuration.ORIENTATION_LANDSCAPE) {

                            // 横屏：仅跳过绘制
                            // 不改变 View 状态
                            // 不影响触摸事件和长按识屏
                            return null;
                        }

                    } catch (Throwable t) {
                        Log.e(TAG,
                                "[H7] orientation check failed: " + t);
                    }

                    // 竖屏或检测失败：正常绘制
                    return chain.proceed();
                });

        Log.i(TAG, "[H7] NavigationHandle.onDraw hook installed");

    } catch (Throwable e) {
        Log.e(TAG, "[H7] hook installation FAILED", e);
    }

    Log.i(TAG, "[H7] installH7_SystemUI end");
}

    // ========== H3.ime: 输入法进程 — 致盲 Resources 让键盘贴底 ==========
    private void installH3_IME() {
        Log.i(TAG, "[H3] installH3_IME start");
        try {
            hook(android.content.res.Resources.class.getDeclaredMethod("getDimensionPixelSize", int.class))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        int resId = (int) chain.getArg(0);
                        try {
                            android.content.res.Resources res = (android.content.res.Resources) chain.getThisObject();
                            String name = res.getResourceEntryName(resId);
                            if ("input_method_navigation_bar_height".equals(name)
                                    || "input_method_nav_buttons_height".equals(name)) {
                                Log.i(TAG, "[H3] getDimen(" + name + ") -> 0");
                                return 0;
                            }
                        } catch (Throwable ignored) {}
                        return chain.proceed();
                    });
            Log.i(TAG, "[H3] hook installed OK");
        } catch (Throwable e) {
            Log.e(TAG, "[H3] hook FAILED: " + e);
        }
        Log.i(TAG, "[H3] installH3_IME end");
    }

