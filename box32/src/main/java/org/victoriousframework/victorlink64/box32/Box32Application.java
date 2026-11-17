package org.victoriousframework.victorlink64.box32;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.ClientConfiguration;

/**
 * Starts the BlackBox container core in every process of this app (main, ":black" server and the
 * virtual app's own ":pN" processes). Failures are logged and never crash the helper's own screen.
 */
public class Box32Application extends Application {
    private static final String TAG = "Box32";
    private static volatile boolean sEngineAttached;
    private static volatile String sEngineError;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // The ":ui" process (HelperActivity) must stay free of engine code so it can report engine failures.
        if (isUiProcess()) return;
        try {
            BlackBoxCore.get().doAttachBaseContext(base, new ClientConfiguration() {
                @Override
                public String getHostPackageName() {
                    return base.getPackageName();
                }

                @Override
                public boolean isEnableDaemonService() {
                    return false;
                }
            });
            sEngineAttached = true;
        } catch (Throwable t) {
            sEngineError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.e(TAG, "Container engine failed to attach", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (isUiProcess() || !sEngineAttached) return;
        try {
            BlackBoxCore.get().doCreate();
        } catch (Throwable t) {
            sEngineAttached = false;
            sEngineError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.e(TAG, "Container engine failed to start", t);
        }
    }

    private boolean isUiProcess() {
        String name = Application.getProcessName();
        return name != null && name.endsWith(":ui");
    }

    static boolean engineReady() {
        return sEngineAttached;
    }

    static String engineError() {
        return sEngineError;
    }
}
