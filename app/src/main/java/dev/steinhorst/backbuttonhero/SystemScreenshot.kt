package dev.steinhorst.backbuttonhero

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcel

/** Runs through AYN's pservice and asks SystemUI for its native screenshot flow. */
@SuppressLint("PrivateApi")
object SystemScreenshot {
    const val BRIDGE_SERVICE = "backbuttonhero_screenshot"
    const val BRIDGE_DESCRIPTOR = "dev.steinhorst.backbuttonhero.IScreenshotBridge"
    const val TRANSACTION_CAPTURE = IBinder.FIRST_CALL_TRANSACTION
    private const val CONNECTION_DESCRIPTOR = "android.app.IServiceConnection"
    private const val BIND_FLAGS = 0x04000001
    private const val TIMEOUT_MS = 10_000L

    fun connect(): Boolean = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val bridge = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, BRIDGE_SERVICE) as? IBinder ?: return false
        val activityThread = Class.forName("android.app.ActivityThread")
            .getMethod("currentActivityThread").invoke(null)
        val applicationThread = activityThread.javaClass
            .getMethod("getApplicationThread").invoke(activityThread) as IBinder
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(BRIDGE_DESCRIPTOR)
            data.writeStrongBinder(applicationThread)
            bridge.transact(TRANSACTION_CAPTURE, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }.getOrDefault(false)

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val displayId = args.single().toInt()
            Looper.prepare()
            val handler = object : Handler(Looper.myLooper()!!) {
                override fun handleMessage(msg: Message) {
                    if (msg.what == 2) finish("OK", 0)
                }
            }
            val request = screenshotRequest(displayId)
            val replyTo = Messenger(handler)
            val bridge = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == INTERFACE_TRANSACTION) {
                        reply?.writeString(BRIDGE_DESCRIPTOR)
                        return true
                    }
                    if (code != TRANSACTION_CAPTURE) return super.onTransact(code, data, reply, flags)
                    data.enforceInterface(BRIDGE_DESCRIPTOR)
                    bindScreenshotService(data.readStrongBinder(), request, replyTo)
                    return true
                }
            }
            addBridgeService(bridge)
            handler.postDelayed({ finish("ERR timeout", 1) }, TIMEOUT_MS)
            Looper.loop()
        } catch (t: Throwable) {
            finish("ERR ${t.javaClass.simpleName}: ${t.cause?.message ?: t.message}", 1)
        }
    }

    private fun bindScreenshotService(applicationThread: IBinder, request: Any, replyTo: Messenger) {
        val connectionBinder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) {
                    reply?.writeString(CONNECTION_DESCRIPTOR)
                    return true
                }
                if (code != 1) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(CONNECTION_DESCRIPTOR)
                data.readTypedObject(ComponentName.CREATOR)
                val service = data.readStrongBinder()
                data.readBoolean()
                if (service == null) finish("ERR screenshot service unavailable", 1)
                Messenger(service).send(Message.obtain(null, 0, request).apply { this.replyTo = replyTo })
                return true
            }
        }
        bindScreenshotService(applicationThread, connectionBinder)
    }

    private fun screenshotRequest(displayId: Int): Any {
        val builderClass = Class.forName("com.android.internal.util.ScreenshotRequest\$Builder")
        val builder = builderClass
            .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(1, 5)
        builderClass.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(builder, displayId)
        return requireNotNull(builderClass.getMethod("build").invoke(builder))
    }

    private fun addBridgeService(bridge: IBinder) {
        val serviceManager = Class.forName("android.os.ServiceManager")
        serviceManager.methods.single { it.name == "addService" && it.parameterTypes.size == 4 }
            .invoke(null, BRIDGE_SERVICE, bridge, false, 0)
    }

    private fun bindScreenshotService(applicationThread: IBinder, connectionBinder: IBinder) {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val activityBinder = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, "activity") as IBinder
        val activityManager = Class.forName("android.app.IActivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, activityBinder)
        val caller = Class.forName("android.app.IApplicationThread\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, applicationThread)
        val connection = Class.forName("android.app.IServiceConnection\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, connectionBinder)
        val intent = Intent().setComponent(
            ComponentName("com.android.systemui", "com.android.systemui.screenshot.TakeScreenshotService")
        )
        val bindService = activityManager.javaClass.methods.single {
            it.name == "bindService" && it.parameterTypes.size == 8
        }
        val result = bindService.invoke(
            activityManager,
            caller,
            null,
            intent,
            null,
            connection,
            BIND_FLAGS,
            "dev.steinhorst.backbuttonhero",
            0,
        ) as Int
        if (result <= 0) finish("ERR bind failed: $result", 1)
    }

    private fun finish(line: String, code: Int): Nothing {
        println(line)
        System.out.flush()
        Runtime.getRuntime().halt(code)
        throw AssertionError()
    }
}
