"""The Thor as the tests see it: the app's settings, the system state they touch, and observers."""

import json
import os
import re
import shlex
import time

from .adb import WORK, Adb, AdbError
from .pad import Seq
from .strings import Strings

PKG = "dev.steinhorst.backbuttonhero"
SERVICE = f"{PKG}/{PKG}.SwitcherService"
ACTION = f"{PKG}.DEVICE_TEST"
STATE_FILE = os.path.join(WORK, "state.json")

# Every system value a test may change; the run restores each one at the end.
SYSTEM_KEYS = [
    "vibrate_on", "is_charging_separation", "force_landscape", "global_gamepad_to_mouse_mode",
    "performance_mode", "fan_mode", "min_refresh_rate", "peak_refresh_rate", "joystick_light_enabled",
    "dual_screen_display_mode", "enable_system_key_focus_lock", "screen_focus_lock",
    "flip_button_layout", "temp_abxy_layout_mode", "no_create_gamepad_button_layout",
    "secondary_screen_volume_level",
]
PROPS = ["persist.sys.input.dispaly", "persist.sys.gamepad.type"]
EMPTY = '""'
ZEN_TO_DND = {"0": "off", "1": "priority", "2": "none", "3": "alarms"}


class Fail(AssertionError):
    pass


# Keys the Home layer of dual mode stores under its own "dual_" prefix (docs/DUAL-MODE.md). Back + Home
# ("home") has no Home layer counterpart.
LAYERED_TIMINGS = {"double_ms", "tap_hold_ms", "hold_ms", "lstick_double_ms", "lstick_tap_hold_ms",
                   "rstick_double_ms", "rstick_tap_hold_ms"}


class Device:
    def __init__(self, serial=None, log=print):
        self.adb = Adb(serial)
        self.log = log
        self.top = 0
        ids = sorted({int(i) for i in re.findall(r"mDisplayId=(\d+)", self.sh("dumpsys display"))})
        others = [i for i in ids if i != 0]
        if not others:
            raise AdbError("no second display found")
        self.bottom = max(others)
        self.locale = self.sh("getprop persist.sys.locale") or "en-US"
        self.text = Strings(self.locale)
        self.warnings = []
        self.last_mark = self.sh("date +'%m-%d %H:%M:%S.000'")
        # "back", or "home" while a layered test runs in dual mode's Home layer.
        self.layer = "back"

    def sh(self, command, timeout=60):
        return self.adb.sh(command, timeout=timeout)

    # --- prerequisites ----------------------------------------------------------------------

    def prepare(self):
        self.adb.ensure_tools()
        self.sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
        self.reconnect()

    def restart_service(self):
        """Turns the service off and on: it comes back active, with nothing held or paused."""
        enabled = [s for s in self.sh("settings get secure enabled_accessibility_services").split(":") if s not in ("", "null")]
        others = [s for s in enabled if s != SERVICE]
        self.sh(f"settings put secure enabled_accessibility_services {shlex.quote(':'.join(others) or 'null')}")
        time.sleep(1)
        self.reconnect()

    def reconnect(self):
        """Enables and binds the service; after a crash Android keeps it listed but unbound."""
        self.sh("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
        if self.service_bound():
            return
        enabled = [s for s in self.sh("settings get secure enabled_accessibility_services").split(":") if s not in ("", "null")]
        others = [s for s in enabled if s != SERVICE]
        self.sh(f"settings put secure enabled_accessibility_services {shlex.quote(':'.join(others) or 'null')}")
        time.sleep(1)
        self.sh(f"settings put secure enabled_accessibility_services {shlex.quote(':'.join(others + [SERVICE]))}")
        self.sh("settings put secure accessibility_enabled 1")
        self.eventually(self.service_bound, 15, "the accessibility service never bound")

    def crashes(self):
        """The app's crash reports so far, newest last."""
        out = self.sh("logcat -d -b crash -v brief")
        blocks = re.split(r"(?=E/AndroidRuntime\(\s*\d+\): FATAL EXCEPTION)", out)
        return [b for b in blocks if f"Process: {PKG}" in b]

    def service_bound(self):
        # Android wraps this section when another accessibility service is also bound.
        dump = self.sh("dumpsys accessibility")
        bound = dump.partition("Bound services:")[2].partition("Enabled services:")[0]
        return "Service[label=Back Button Hero," in bound

    def helper_pid(self):
        return self.sh("pidof libpadhelper.so")

    # --- the app's settings -----------------------------------------------------------------

    def app(self, op, **extras):
        args = [f"am broadcast -a {ACTION} -p {PKG} --es op {op}"]
        for key, value in extras.items():
            args.append(f"--es {key} {shlex.quote(str(value))}")
        out = self.sh(" ".join(args))
        m = re.search(r'data="(.*)"', out)
        result = m.group(1) if m else out
        if result.startswith("ERR") or result == "FAIL":
            raise AdbError(f"app {op} {extras}: {result}")
        return result

    def layered(self, key):
        """[key] in the layer under test: the Home layer's own key while it runs."""
        return f"dual_{key}" if self.layer == "home" and key in LAYERED_TIMINGS else key

    def binding(self, pref):
        """The preference key of hotkey [pref] in the layer under test."""
        if self.layer != "home":
            return pref
        if pref == "home":
            raise AdbError("Back + Home has no Home layer counterpart")
        return f"dual_{pref}"

    @property
    def modifier(self):
        """The evdev code of the layer's modifier: Back, or Home in dual mode's Home layer."""
        from . import pad
        return pad.modifier

    def put(self, key, value):
        key = self.layered(key)
        # Every duration the app stores is a Long; reading an Int there would crash the service.
        kind = ("bool" if isinstance(value, bool) else "long" if isinstance(value, int) and key.endswith("_ms")
                else "int" if isinstance(value, int) else "float" if isinstance(value, float) else "string")
        self.app("put", key=key, type=kind, value=str(value).lower() if kind == "bool" else value)

    def bind(self, pref, action, **options):
        """Assigns [action] to the hotkey with preference key [pref]; options are its sub-settings."""
        pref = self.binding(pref)
        self.put(f"{pref}_package", action)
        for name, value in options.items():
            self.put(f"{pref}_{name}", value)

    def unbind(self, *prefs):
        for pref in prefs:
            self.put(f"{self.binding(pref)}_package", "")

    def prefs_xml(self):
        return self.sh(f"run-as {PKG} cat shared_prefs/back_button_hero.xml")

    # --- system values ----------------------------------------------------------------------

    def setting(self, key, namespace="system"):
        return self.sh(f"settings get {namespace} {key}")

    def root(self, command):
        return self.adb.root_line(command)

    def brightness(self):
        out = self.adb.probe("get", str(self.top), str(self.bottom)).split()
        if len(out) != 2 or out[0].startswith("ERR"):
            raise Fail(f"brightness unreadable: {out}")
        return float(out[0]), float(out[1])

    def volume(self):
        out = self.sh("cmd media_session volume --stream 3 --get")
        top = int(re.search(r"volume is (\d+)", out).group(1))
        return top, int(self.setting("secondary_screen_volume_level"))

    def microphone_blocked(self):
        dump = self.sh("dumpsys sensor_privacy")
        m = re.search(r"sensor=1\s+toggles=\{\s+toggle_type=1\s+state_type=(\d)", dump)
        return bool(m) and m.group(1) == "1"

    def snapshot_system(self):
        state = {
            "system": {k: self.setting(k) for k in SYSTEM_KEYS},
            "props": {p: self.sh(f"getprop {p}") for p in PROPS},
            "zen": self.setting("zen_mode", "global"),
            "mic": self.microphone_blocked(),
            "volume": self.volume()[0],
            "brightness": self.brightness(),
        }
        return state

    def restore_system(self, state):
        lines = []
        for key, value in state["system"].items():
            current = self.setting(key)
            if value != current:
                lines.append(f"settings delete system {key}" if value == "null" else f"settings put system {key} {shlex.quote(value)}")
        for prop, value in state["props"].items():
            if self.sh(f"getprop {prop}") != value:
                lines.append(f"setprop {prop} {shlex.quote(value) if value else EMPTY}")
        if self.setting("zen_mode", "global") != state["zen"]:
            lines.append(f"cmd notification set_dnd {ZEN_TO_DND.get(state['zen'], 'off')}")
        if self.microphone_blocked() != state["mic"]:
            lines.append(f"cmd sensor_privacy {'enable' if state['mic'] else 'disable'} 0 microphone")
        if lines:
            self.adb.root_script("\n".join(lines) + "\n")
        self.sh(f"cmd media_session volume --stream 3 --set {state['volume']}")
        for display, value in zip((self.top, self.bottom), state["brightness"]):
            self.adb.probe("set", str(display), str(value))

    def begin(self):
        """Saves the owner's settings once; a saved state from a crashed run is never overwritten."""
        os.makedirs(WORK, exist_ok=True)
        if os.path.exists(STATE_FILE):
            raise AdbError(f"a previous run did not finish; run with --restore first ({STATE_FILE})")
        prefs = self.prefs_xml()
        with open(os.path.join(WORK, f"owner-prefs-{time.strftime('%Y%m%d-%H%M%S')}.xml"), "w") as f:
            f.write(prefs)
        kept = self.app("snapshot")
        state = {"system": self.snapshot_system(), "app_snapshot": kept}
        with open(STATE_FILE, "w") as f:
            json.dump(state, f, indent=2)
        self.app("reset")

    def end(self):
        if not os.path.exists(STATE_FILE):
            return False
        state = json.load(open(STATE_FILE))
        self.app("media", value="stop")
        self.app("restore")
        self.restore_system(state["system"])
        os.unlink(STATE_FILE)
        return True

    # --- input ------------------------------------------------------------------------------

    def mark(self):
        """The device clock now, for reading only the log lines that follow."""
        self.last_mark = self.sh("date +'%m-%d %H:%M:%S.000'")
        return self.last_mark

    def press(self, seq: Seq):
        self.mark()
        out = self.adb.root_script(seq.script())
        if "ERR no pad" in out:
            raise Fail("the pad device was not found")
        return out

    def clone_node(self):
        """The input node of the pad apps see: the helper's clone, or AYN's pad without the helper."""
        devices = self.sh("cat /proc/bus/input/devices")
        nodes = re.findall(r'N: Name="Xbox Wireless Controller"[^\n]*\n(?:[^\n]+\n)*?H: Handlers=[^\n]*?(event\d+)', devices)
        return f"/dev/input/{nodes[-1]}"

    def press_observing(self, seq: Seq, check, interval=0.15):
        """Presses [seq] while polling [check] alongside; returns every truthy result seen, for states
        that only last a moment (a pictogram, a toast) and may be gone once the press returns."""
        import threading
        seen, done = [], threading.Event()

        def poll():
            while not done.is_set():
                result = check()
                if result:
                    seen.append(result)
                time.sleep(interval)
        thread = threading.Thread(target=poll, daemon=True)
        thread.start()
        try:
            self.press(seq)
            time.sleep(0.5)
        finally:
            done.set()
            thread.join(3)
        return seen

    def press_watching(self, seq: Seq, settle=0.6):
        """Presses [seq] and returns (type, code, value) of every event the game-facing pad emitted.
        getevent runs on a terminal so its output is line-buffered rather than lost when it is stopped."""
        import subprocess
        import threading
        proc = subprocess.Popen(["adb", "-s", self.adb.serial, "shell", "-tt", "getevent", self.clone_node()],
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        lines = []
        reader = threading.Thread(target=lambda: lines.extend(iter(proc.stdout.readline, "")), daemon=True)
        reader.start()
        time.sleep(0.8)
        try:
            self.press(seq)
            time.sleep(settle)
        finally:
            proc.terminate()
            reader.join(2)
        return [tuple(int(x, 16) for x in m) for m in re.findall(r"([0-9a-f]{4}) ([0-9a-f]{4}) ([0-9a-f]{8})", "".join(lines))]

    def face(self, letter):
        """The evdev code of the face button printed [letter]; Xbox Style swaps A/B and X/Y."""
        flipped = self.setting("flip_button_layout") == "1"
        return {"a": 305, "b": 304, "x": 308, "y": 307}[letter] if flipped else {"a": 304, "b": 305, "x": 307, "y": 308}[letter]

    def keyevent(self, code, display=None):
        self.sh(f"input {'-d ' + str(display) + ' ' if display is not None else ''}keyevent {code}")

    # --- observers --------------------------------------------------------------------------

    def eventually(self, check, timeout, message, interval=0.2):
        deadline = time.time() + timeout
        last = None
        while True:
            last = check()
            if last:
                return last
            if time.time() > deadline:
                raise Fail(message)
            time.sleep(interval)

    NOTICE_WINDOW = "BackButtonHeroNotice"

    def notice_visible(self):
        return self.NOTICE_WINDOW in self.windows(self.top)

    def notices(self):
        """Every message the app's notice showed since the last press, oldest first."""
        lines = self.sh(f"logcat -d -v brief -T {shlex.quote(self.last_mark)} BackButtonHero:I *:S")
        return [m.replace("\\n", "\n") for m in re.findall(r"Notice: (.*)", lines)]

    def toasts(self):
        """The message on screen now, if any (the app's notice replaced Android's rate-limited toasts)."""
        if not self.notice_visible():
            return []
        shown = self.notices()
        return shown[-1:] if shown else ["?"]

    def expect_toast(self, text, timeout=3.0):
        """The app showed [text] in its notice, and the notice is really on screen."""
        try:
            self.eventually(lambda: text in self.notices() and self.notice_visible(), timeout, "")
        except Fail:
            raise Fail(f"notice {text!r} not shown (saw {self.notices()}, visible {self.notice_visible()})")

    def wait_toasts_gone(self, timeout=6.0):
        try:
            self.eventually(lambda: not self.notice_visible(), timeout, "")
        except Fail:
            pass

    def clear_log(self):
        self.sh("logcat -c")

    def log_lines(self, pattern):
        return [l for l in self.sh("logcat -d -v brief").splitlines() if re.search(pattern, l)]

    def media_keys(self):
        return re.findall(r"MEDIA_KEY KEYCODE_(\w+)", "\n".join(self.log_lines("DeviceTest")))

    def focus(self, display):
        """Routes the controller to [display] the way AYN's own tool does (both values are restored by end())."""
        value = "1" if display == self.top else "2"
        self.root(f"setprop persist.sys.input.dispaly {value} && settings put system screen_focus_lock {value} "
                  "&& input keyevent 60 && echo OK")
        self.eventually(lambda: self.route() == display, 4, f"the controller route did not move to display {display}")

    def route(self):
        """The display AYN routes the controller to. Not Android's focused display, which follows touch and launches."""
        return {"1": self.top, "2": self.bottom}.get(self.setting("screen_focus_lock"))

    def focused_display(self):
        """The display whose window gets controller input: the top focused one."""
        m = re.search(r"mTopFocusedDisplayId=(-?\d+)", self.sh("dumpsys window | grep -m1 mTopFocusedDisplayId"))
        return int(m.group(1)) if m else None

    def probe_start(self, display=0):
        """Opens the stand-in game on [display], routes the controller there and waits for both."""
        self.app("probe", value="start", display=display)
        self.eventually(lambda: "DeviceTestKeyActivity" in self.resumed().get(display, ""), 5,
                        "the stand-in game did not come to the front")
        self.focus(display)
        time.sleep(0.5)

    def probe_stop(self):
        self.app("probe", value="stop")

    def probe_events(self):
        """What reached the stand-in game since the last clear_log: ('KEY', 'DOWN', 'KEYCODE_X') or ('AXIS', 'AXIS_HAT_X', '-1')."""
        return [tuple(m.groups()) for l in self.log_lines("DeviceTest")
                for m in [re.search(r"PROBE_(KEY|AXIS) (\S+) (\S+)", l)] if m]

    # --- apps and windows ---------------------------------------------------------------------

    def start_app(self, component, display):
        self.sh(f"am start -W --display {display} -n {component}")
        pkg = component.split("/")[0]
        self.eventually(lambda: self.resumed().get(display, "").startswith(pkg + "/"), 6,
                        f"{component} did not come to the front on display {display}")

    def stop_app(self, pkg):
        self.sh(f"am force-stop {pkg}")

    def pid(self, pkg):
        return self.sh(f"pidof {pkg}").strip()

    def window_owners(self, display=None):
        """(title, owning pid) of every window, optionally only those on [display]."""
        out, title = [], None
        for line in self.sh("dumpsys window windows").splitlines():
            m = re.match(r"\s+Window #\d+ Window\{\S+ \S+ (.*)\}:", line)
            if m:
                title = m.group(1)
                continue
            m = re.search(r"mDisplayId=(\d+) .*?mSession=Session\{\S+ (\d+):", line)
            if m and title is not None:
                if display is None or int(m.group(1)) == display:
                    out.append((title, int(m.group(2))))
                title = None
        return out

    def windows(self, display):
        """Window titles on [display]."""
        return [t for t, _ in self.window_owners(display)]

    def own_windows(self, display):
        """Windows the app itself shows on [display]: overlays, prompts, the switcher."""
        pid = self.pid(PKG)
        # Activity windows are named package/class; overlays, prompts and the switcher are not.
        return [t for t, p in self.window_owners(display) if str(p) == pid and "/" not in t and t != self.NOTICE_WINDOW]

    def awake(self):
        return "mWakefulness=Awake" in self.sh("dumpsys power | grep -m1 mWakefulness=")

    def files(self, directory):
        return set(self.sh(f"ls {directory} 2>/dev/null").split())

    def resumed(self):
        """The top resumed activity per display, as package/activity."""
        out = {}
        display = None
        for line in self.sh("dumpsys activity activities").splitlines():
            m = re.match(r"Display #(\d+)", line)
            if m:
                display = int(m.group(1))
                continue
            m = re.search(r"topResumedActivity=ActivityRecord\{\S+ \S+ (\S+)", line)
            if m and display is not None and display not in out:
                out[display] = m.group(1)
        return out
