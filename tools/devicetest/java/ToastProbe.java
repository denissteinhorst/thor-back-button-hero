// SPDX-License-Identifier: 0BSD
import android.os.Binder;
import android.os.IBinder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Posts one text toast as the shell and reports whether SystemUI's toast window becomes visible: VISIBLE or
 * HIDDEN. Started by the harness with app_process from the adb shell (package com.android.shell), so the
 * toast belongs to a caller the "Hide pop-ups" app-op must hide like any other. Android 11+ hands text toasts
 * to SystemUI, which draws them in a window titled "Toast".
 */
public final class ToastProbe {
    public static void main(String[] args) throws Exception {
        String text = args.length > 0 ? args[0] : "Back Button Hero toast probe";
        IBinder service = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "notification");
        Object manager = Class.forName("android.app.INotificationManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, service);
        Method enqueue = null;
        for (Method m : manager.getClass().getMethods()) {
            if (m.getName().equals("enqueueTextToast")) enqueue = m;
        }
        if (enqueue == null) {
            System.out.println("ERR no enqueueTextToast");
            return;
        }
        // The signature differs between releases: fill by type, the first int is the duration (1 = long).
        Class<?>[] types = enqueue.getParameterTypes();
        Object[] values = new Object[types.length];
        boolean duration = false;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == String.class) values[i] = "com.android.shell";
            else if (t == IBinder.class) values[i] = new Binder();
            else if (t == CharSequence.class) values[i] = text;
            else if (t == int.class) { values[i] = duration ? 0 : 1; duration = true; }
            else if (t == boolean.class) values[i] = false;
            else values[i] = null;
        }
        enqueue.invoke(manager, values);
        long deadline = System.currentTimeMillis() + 2500;
        while (System.currentTimeMillis() < deadline) {
            if (toastVisible()) {
                System.out.println("VISIBLE");
                return;
            }
            Thread.sleep(150);
        }
        System.out.println("HIDDEN");
    }

    private static boolean toastVisible() throws Exception {
        Process p = new ProcessBuilder("dumpsys", "window", "windows").redirectErrorStream(true).start();
        StringBuilder all = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            for (String line; (line = r.readLine()) != null; ) all.append(line).append('\n');
        }
        p.waitFor();
        for (String block : all.toString().split("\n  Window #")) {
            if (block.contains("u0 Toast}") && block.contains("isVisible=true")) return true;
        }
        return false;
    }
}
