package org.victoriousframework.victorlink64.box32;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.util.Log;

import java.io.File;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;

/**
 * Lives in the default process, which is where the container engine runs. HelperActivity (another process)
 * asks it two things: STATUS (did the engine start?) and RUN (add this APK and start it).
 */
public class EngineService extends Service {
    private static final String TAG = "Box32Engine";

    public static final int MSG_STATUS = 1;
    public static final int MSG_RUN = 2;
    public static final int MSG_REPLY = 3;

    public static final String KEY_PATH = "path";
    public static final String KEY_ENGINE_READY = "engine_ready";
    public static final String KEY_ENGINE_PID = "engine_pid";
    public static final String KEY_ENGINE_64_BIT = "engine_is64bit";

    private HandlerThread thread;
    private Messenger messenger;

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("box32-engine");
        thread.start();
        messenger = new Messenger(new Handler(thread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                Bundle out;
                if (msg.what == MSG_STATUS) {
                    out = status();
                } else if (msg.what == MSG_RUN) {
                    out = run(msg.getData() == null ? null : msg.getData().getString(KEY_PATH));
                } else {
                    return;
                }
                if (msg.replyTo == null) return;
                Message reply = Message.obtain(null, MSG_REPLY);
                reply.setData(out);
                try {
                    msg.replyTo.send(reply);
                } catch (Exception e) {
                    Log.w(TAG, "Caller went away before the reply", e);
                }
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        thread.quitSafely();
        super.onDestroy();
    }

    private Bundle status() {
        Bundle b = new Bundle();
        b.putBoolean(KEY_ENGINE_READY, Box32Application.engineReady());
        b.putInt(KEY_ENGINE_PID, Process.myPid());
        b.putBoolean(KEY_ENGINE_64_BIT, Process.is64Bit());
        b.putString(HelperProtocol.RESULT_MESSAGE, Box32Application.engineReady()
                ? "BlackBox container core attached (not yet proven on a device)"
                : "BlackBox container core is not running: " + Box32Application.engineError());
        return b;
    }

    private Bundle run(String path) {
        Bundle b = status();
        if (path == null) return fail(b, "No APK path was given to the engine.");
        if (!Box32Application.engineReady()) {
            return fail(b, "The container engine did not start: " + Box32Application.engineError());
        }
        try {
            InstallResult install = BlackBoxCore.get().installPackageAsUser(new File(path), 0);
            if (install == null || !install.success) {
                return fail(b, "The container could not add this app: " + (install == null ? "no result" : install.msg));
            }
            b.putString(HelperProtocol.RESULT_PACKAGE, install.packageName);
            if (!BlackBoxCore.get().launchApk(install.packageName, 0)) {
                return fail(b, "The app was added to the sandbox but has no screen to open (no launcher activity).");
            }
            b.putString(HelperProtocol.RESULT_STATUS, HelperProtocol.STATUS_LAUNCHED);
            b.putString(HelperProtocol.RESULT_MESSAGE, "Start requested. If nothing appears, check logcat for the sandboxed app's process.");
            return b;
        } catch (Throwable t) {
            Log.e(TAG, "RUN failed", t);
            return fail(b, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static Bundle fail(Bundle b, String message) {
        b.putString(HelperProtocol.RESULT_STATUS, HelperProtocol.STATUS_ERROR);
        b.putString(HelperProtocol.RESULT_MESSAGE, message);
        return b;
    }
}
