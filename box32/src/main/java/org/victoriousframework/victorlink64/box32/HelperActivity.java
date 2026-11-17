package org.victoriousframework.victorlink64.box32;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;


/**
 * The helper's only entry point. It answers two requests from VictorLink64:
 * PING (report whether this process is really 32-bit) and RUN_APK (copy the APK into this app's private
 * storage, add it to the container and start it). Requests from any other app are rejected.
 */
public class HelperActivity extends Activity {
    private static final String TAG = "Box32Helper";
    private static final long MAX_APK_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int NAVY = 0xFF0A1128;
    private static final int CYAN = 0xFF1EA0FF;
    private static final int MUTED = 0xFF8FA3C8;

    private static final long ENGINE_TIMEOUT_MS = 20_000;

    private TextView status;
    private ServiceConnection engineConnection;
    private boolean engineAnswered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
        Intent intent = getIntent();
        String action = intent == null ? null : intent.getAction();
        if (HelperProtocol.ACTION_PING.equals(action)) {
            if (!callerIsTrusted()) return;
            status.setText("Checking the helper…");
            askEngine(EngineService.MSG_STATUS, null, (ok, engine) -> replyAndFinish(pingResult(ok, engine)));
        } else if (HelperProtocol.ACTION_RUN_APK.equals(action)) {
            if (!callerIsTrusted()) return;
            runApk(intent);
        } else {
            status.setText(describeSelf());
        }
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(NAVY);
        root.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("VictorLink64 32-bit Helper");
        title.setTextColor(CYAN);
        title.setTextSize(22);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(15);
        status.setPadding(0, pad / 2, 0, 0);
        root.addView(status);

        TextView hint = new TextView(this);
        hint.setText("This small app lets VictorLink64 run old 32-bit apps. Open VictorLink64 and use the APK Player; there is nothing to do here.");
        hint.setTextColor(MUTED);
        hint.setTextSize(13);
        hint.setPadding(0, pad, 0, 0);
        root.addView(hint);
        setContentView(root);
    }

    /** True only when the caller is the matching VictorLink64 build signed with the same certificate. */
    private boolean callerIsTrusted() {
        String caller = getCallingPackage();
        if (caller == null || !caller.equals(expectedMainPackage())
                || getPackageManager().checkSignatures(caller, getPackageName()) != PackageManager.SIGNATURE_MATCH) {
            Log.w(TAG, "Rejected request from " + caller);
            Intent result = new Intent()
                    .putExtra(HelperProtocol.RESULT_STATUS, HelperProtocol.STATUS_REJECTED)
                    .putExtra(HelperProtocol.RESULT_MESSAGE,
                            "The helper only accepts requests from the VictorLink64 app that was signed with the same key.");
            replyAndFinish(result);
            return false;
        }
        return true;
    }

    /** "...victorlink64.box32[.debug]" belongs to "...victorlink64[.debug]". */
    String expectedMainPackage() {
        String own = getPackageName();
        if (own.endsWith(".box32.debug")) return own.substring(0, own.length() - ".box32.debug".length()) + ".debug";
        if (own.endsWith(".box32")) return own.substring(0, own.length() - ".box32".length());
        return own;
    }

    /** The helper's own process is always reported; the engine is reported separately because it can fail on its own. */
    private Intent pingResult(boolean answered, Bundle engine) {
        Intent result = new Intent()
                .putExtra(HelperProtocol.RESULT_STATUS, HelperProtocol.STATUS_OK)
                .putExtra(HelperProtocol.RESULT_PROTOCOL, HelperProtocol.VERSION)
                .putExtra(HelperProtocol.RESULT_IS_64_BIT, Process.is64Bit())
                .putExtra(HelperProtocol.RESULT_ABIS, Arrays.toString(Build.SUPPORTED_ABIS))
                .putExtra(HelperProtocol.RESULT_PID, Process.myPid());
        String engineText;
        if (!answered) {
            engineText = engine.getString(HelperProtocol.RESULT_MESSAGE);
        } else {
            engineText = engine.getString(HelperProtocol.RESULT_MESSAGE) + " (engine process "
                    + engine.getInt(EngineService.KEY_ENGINE_PID) + ", "
                    + (engine.getBoolean(EngineService.KEY_ENGINE_64_BIT) ? "64-bit" : "32-bit") + ")";
        }
        return result.putExtra(HelperProtocol.RESULT_ENGINE, engineText);
    }

    private String describeSelf() {
        return "Running as " + (Process.is64Bit() ? "64-bit" : "32-bit") + " (pid " + Process.myPid() + ").\n"
                + "Phone chip types: " + Arrays.toString(Build.SUPPORTED_ABIS);
    }

    private void runApk(Intent intent) {
        Uri uri = intent.getData();
        if (uri == null) {
            replyAndFinish(error("No APK was attached to the request."));
            return;
        }
        status.setText("Preparing the app…");
        Thread worker = new Thread(() -> {
            try {
                File apk = copyToPrivateStorage(uri);
                runOnUiThread(() -> {
                    status.setText("Adding the app to the sandbox…");
                    Bundle data = new Bundle();
                    data.putString(EngineService.KEY_PATH, apk.getAbsolutePath());
                    askEngine(EngineService.MSG_RUN, data, (ok, engine) -> {
                        Intent result = new Intent()
                                .putExtra(HelperProtocol.RESULT_STATUS,
                                        ok ? engine.getString(HelperProtocol.RESULT_STATUS, HelperProtocol.STATUS_ERROR) : HelperProtocol.STATUS_ERROR)
                                .putExtra(HelperProtocol.RESULT_MESSAGE, engine.getString(HelperProtocol.RESULT_MESSAGE, ""));
                        String pkg = engine.getString(HelperProtocol.RESULT_PACKAGE);
                        if (pkg != null) result.putExtra(HelperProtocol.RESULT_PACKAGE, pkg);
                        replyAndFinish(result);
                    });
                });
            } catch (Throwable t) {
                Log.e(TAG, "RUN_APK failed", t);
                runOnUiThread(() -> replyAndFinish(error(t.getClass().getSimpleName() + ": " + t.getMessage())));
            }
        }, "box32-copy-apk");
        worker.start();
    }

    private interface EngineCallback {
        /** @param answered true if the engine process replied; false if it did not (the bundle then holds the reason) */
        void done(boolean answered, Bundle engine);
    }

    /** One request to the engine process, with a timeout: a native crash on startup simply never answers. */
    private void askEngine(int what, Bundle data, EngineCallback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        Runnable timeout = () -> finishEngineCall(false, failure(
                "The container engine did not answer within " + (ENGINE_TIMEOUT_MS / 1000) + " seconds. It most likely crashed while "
                        + "starting, which is the expected outcome on a phone where the engine does not work. Capture `adb logcat` "
                        + "(look for libblackbox, BlackBoxCore, SIGSEGV) to see why."), callback);
        Messenger replyTo = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
            main.removeCallbacks(timeout);
            finishEngineCall(true, msg.getData(), callback);
            return true;
        }));
        engineConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                Message m = Message.obtain(null, what);
                m.replyTo = replyTo;
                if (data != null) m.setData(data);
                try {
                    new Messenger(binder).send(m);
                } catch (Exception e) {
                    main.removeCallbacks(timeout);
                    finishEngineCall(false, failure("Could not talk to the container engine: " + e.getMessage()), callback);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                main.removeCallbacks(timeout);
                finishEngineCall(false, failure("The container engine process stopped (it probably crashed). Capture `adb logcat` to see why."), callback);
            }

            @Override
            public void onBindingDied(ComponentName name) {
                onServiceDisconnected(name);
            }

            @Override
            public void onNullBinding(ComponentName name) {
                main.removeCallbacks(timeout);
                finishEngineCall(false, failure("The container engine service has no interface."), callback);
            }
        };
        main.postDelayed(timeout, ENGINE_TIMEOUT_MS);
        if (!bindService(new Intent(this, EngineService.class), engineConnection, Context.BIND_AUTO_CREATE)) {
            main.removeCallbacks(timeout);
            finishEngineCall(false, failure("Could not start the container engine process."), callback);
        }
    }

    private static Bundle failure(String message) {
        Bundle b = new Bundle();
        b.putString(HelperProtocol.RESULT_STATUS, HelperProtocol.STATUS_ERROR);
        b.putString(HelperProtocol.RESULT_MESSAGE, message);
        return b;
    }

    private void finishEngineCall(boolean answered, Bundle bundle, EngineCallback callback) {
        if (engineAnswered) return;
        engineAnswered = true;
        callback.done(answered, bundle == null ? failure("Empty answer from the engine.") : bundle);
    }

    private File copyToPrivateStorage(Uri uri) throws IOException {
        File dir = new File(getFilesDir(), "incoming");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create private storage");
        File tmp = new File(dir, UUID.randomUUID() + ".tmp");
        try (InputStream raw = getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("Cannot open the shared file");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = new DigestInputStream(raw, digest); OutputStream out = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    total += n;
                    if (total > MAX_APK_BYTES) throw new IOException("File is too large");
                    out.write(buf, 0, n);
                }
            }
            if (total == 0) throw new IOException("File is empty");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            File apk = new File(dir, hex.substring(0, 24) + ".apk");
            if (apk.exists() && !apk.delete()) throw new IOException("Cannot replace the previous copy");
            if (!tmp.renameTo(apk)) throw new IOException("Cannot store the app");
            return apk;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    private static Intent error(String message) {
        return new Intent()
                .putExtra(HelperProtocol.RESULT_STATUS, HelperProtocol.STATUS_ERROR)
                .putExtra(HelperProtocol.RESULT_MESSAGE, message);
    }

    private void replyAndFinish(Intent result) {
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (engineConnection != null) {
            try {
                unbindService(engineConnection);
            } catch (RuntimeException ignored) {
                // never bound or already unbound
            }
            engineConnection = null;
        }
        super.onDestroy();
    }
}
