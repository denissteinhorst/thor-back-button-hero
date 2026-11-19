import android.os.IBinder;
import java.lang.reflect.Method;

/**
 * Reads or sets the brightness of individual displays for the device test harness, which needs
 * the bottom screen's value too and has no shell command for it. Runs as root (via RootExec) with
 * app_process and prints one line: `get 0 4` answers "0.42 0.31", `set 4 0.31` answers "OK".
 */
public class DisplayProbe {
    public static void main(String[] args) {
        try {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "display");
            Object manager = Class.forName("android.hardware.display.IDisplayManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            Method get = null;
            Method set = null;
            for (Method m : manager.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("getBrightness") && p.length == 1 && p[0] == int.class) get = m;
                if (m.getName().equals("setBrightness") && p.length == 2 && p[0] == int.class && p[1] == float.class) set = m;
            }
            if ("set".equals(args[0])) {
                set.invoke(manager, Integer.parseInt(args[1]), Float.parseFloat(args[2]));
                System.out.println("OK");
            } else {
                StringBuilder out = new StringBuilder();
                for (int i = 1; i < args.length; i++) {
                    if (out.length() > 0) out.append(' ');
                    out.append(get.invoke(manager, Integer.parseInt(args[i])));
                }
                System.out.println(out);
            }
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            System.out.println("ERR " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
        // halt: reflection started binder threads that would keep app_process alive.
        Runtime.getRuntime().halt(0);
    }
}
