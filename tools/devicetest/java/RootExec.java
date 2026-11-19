import android.os.IBinder;
import android.os.Parcel;

/**
 * Runs one shell command as root through AYN's preinstalled pservice daemon, the backend of the
 * Thor's "Run Script as Root" setting. Started by the device test harness with app_process from the
 * adb shell, which cannot reach the daemon's binder through `service call`. pservice answers with
 * the command's first output line only, so longer work goes into a script file.
 */
public class RootExec {
    public static void main(String[] args) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "PServerBinder");
        if (binder == null) {
            System.out.println("ERR no pservice");
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeStringArray(new String[] {args[0], "0"});
        boolean ok = binder.transact(0, data, reply, 0);
        byte[] out = reply.createByteArray();
        System.out.println(ok ? (out == null ? "" : new String(out)) : "ERR transact");
    }
}
