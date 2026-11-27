"""The Thor as the tests see it: the app's settings, the system state they touch, and observers."""

import hashlib
import json
import os
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ElementTree

from .adb import JAVA_HOME, WORK, Adb, AdbError, _sdk_dir
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


class RecoveryError(AdbError):
    """The owner's settings could not be saved or verifiably restored. The saved state is kept."""


PREF_TYPES = {"string", "int", "long", "boolean", "float", "set"}


def parse_prefs(text):
    """The typed content of a SharedPreferences XML file: name -> (type, value). Raises ValueError for
    anything that is not such a file, so a failed read can never pass for an empty settings map."""
    try:
        root = ElementTree.fromstring(text)
    except ElementTree.ParseError as e:
        raise ValueError(f"not a settings file: {e}") from None
    if root.tag != "map":
        raise ValueError(f"not a settings file: root <{root.tag}>")
    prefs = {}
    for el in root:
        name = el.get("name")
        if el.tag not in PREF_TYPES or name is None or name in prefs:
            raise ValueError(f"unexpected settings entry <{el.tag} name={name!r}>")
        if el.tag == "string":
            value = el.text or ""
        elif el.tag == "set":
            value = frozenset(child.text or "" for child in el)
        else:
            value = el.get("value")
            if value is None:
                raise ValueError(f"settings entry {name!r} has no value")
        prefs[name] = (el.tag, value)
    return prefs


def prefs_differences(saved, current):
    """Every key whose presence, type or value differs, for the recovery error message."""
    return [f"{key}: saved {saved.get(key)} now {current.get(key)}"
            for key in sorted(set(saved) | set(current)) if saved.get(key) != current.get(key)]


def snapshot_count(reply):
    """The number of settings the app copied aside; anything but a fresh complete copy is refused."""
    m = re.fullmatch(r"OK (\d+)", reply)
    if not m:
        raise RecoveryError(f"the app did not take a fresh copy of the settings: {reply!r}"
                            + (" (an earlier copy exists; run with --restore or see docs/DEVICE-TEST-SUITE.md)"
                               if reply.startswith("KEPT") else ""))
    return int(m.group(1))


def restore_count(reply):
    """The restored settings count; None when the app holds no copy of this run (none, or another
    run's, which is never adopted); an error for anything else."""
    if reply == "NONE" or reply.startswith("OTHER "):
        return None
    m = re.fullmatch(r"OK (\d+)", reply)
    if not m:
        raise RecoveryError(f"the app could not restore the settings: {reply!r}")
    return int(m.group(1))


# Keys the Home layer of dual mode stores under its own "dual_" prefix (docs/HOME-DUAL-MODE.md). Back + Home
# ("home") has no Home layer counterpart.
LAYERED_TIMINGS = {"double_ms", "tap_hold_ms", "hold_ms", "lstick_double_ms", "lstick_tap_hold_ms",
                   "rstick_double_ms", "rstick_tap_hold_ms"}


def file_sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


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
        # Language alone is insufficient: en-DE uses a comma, while de-CH uses a point.
        decimal = self.sh(f"CLASSPATH={self.adb.dex} app_process /system/bin LocaleProbe {shlex.quote(self.locale)}")
        if len(decimal) != 1:
            raise AdbError("the device's decimal separator could not be read")
        self.text = Strings(self.locale, decimal_separator=decimal)
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
        if result.startswith(("ERR", "FAIL")):
            raise AdbError(f"app {op} {extras}: {result}")
        return result

    def app_reply(self, op, **extras):
        """Like [app], but hands every reply to the caller, which checks it against its own contract."""
        args = [f"am broadcast -a {ACTION} -p {PKG} --es op {op}"]
        for key, value in extras.items():
            args.append(f"--es {key} {shlex.quote(str(value))}")
        m = re.search(r'data="(.*)"', self.sh(" ".join(args)))
        return m.group(1) if m else ""

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
                else "int" if isinstance(value, int) else "float" if isinstance(value, float)
                else "set" if isinstance(value, (set, frozenset)) else "string")
        text = str(value).lower() if kind == "bool" else ",".join(sorted(value)) if kind == "set" else value
        self.app("put", key=key, type=kind, value=text)

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

    def restore_system(self, state, passes=3, settle=1.0):
        """Writes the saved system values back until they hold. AYN reacts to some of them on its own
        and asynchronously (a changed performance_mode rewrites fan_mode afterwards), so a value set in
        the same pass can be overwritten again; a later pass only rewrites what still differs."""
        for _ in range(passes):
            self._restore_system_once(state)
            time.sleep(settle)
            if not self.system_differences(state):
                return

    def _restore_system_once(self, state):
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
        """Saves the owner's settings before anything changes them; a saved state from an unfinished
        run is never overwritten. The host keeps the settings file and the system values, the app a
        complete copy; both stay until [end] has verified the restore."""
        os.makedirs(WORK, exist_ok=True)
        if os.path.exists(STATE_FILE):
            raise AdbError(f"a previous run did not finish; run with --restore first ({STATE_FILE})")
        run = time.strftime("%Y%m%d-%H%M%S")
        xml = self.prefs_xml()
        saved = parse_prefs(xml)
        xml_path = os.path.join(WORK, f"owner-prefs-{run}.xml")
        with open(xml_path, "w") as f:
            f.write(xml)
        # The installed artifact is kept too, so a session that installs other builds ends on this one.
        state = {"run": run, "prefs_xml": xml_path, "count": len(saved), "system": self.snapshot_system(),
                 "installed": self.save_installed(run), "stage": "saving"}
        self._write_state(state)
        count = snapshot_count(self.app_reply("snapshot", run=run))
        if count != len(saved):
            raise RecoveryError(f"the app copied {count} settings, the settings file holds {len(saved)}")
        state["stage"] = "saved"
        self._write_state(state)
        self.app("reset")

    def end(self):
        """Restores and verifies the owner's settings and system values. Anything uncertain raises
        [RecoveryError] and keeps every saved copy, so the restore can simply be repeated."""
        if not os.path.exists(STATE_FILE):
            return False
        with open(STATE_FILE) as f:
            state = json.load(f)
        with open(state["prefs_xml"]) as f:
            xml = f.read()
        saved = parse_prefs(xml)
        installed = state.get("installed")
        if installed and self.installed_sha() != installed["sha256"]:
            self.reinstall_original(installed)
        try:
            self.app("media", value="stop")
        except AdbError as e:
            self.log(f"media session not stopped: {e}")
        if restore_count(self.app_reply("restore", run=state["run"])) is None:
            # The app has no complete copy (the run stopped before it took one, or after it dropped it):
            # the host's own settings file is the source.
            self.restore_prefs_file(xml)
        current = parse_prefs(self.prefs_xml())
        differences = prefs_differences(saved, current)
        if differences:
            raise RecoveryError("restored settings differ from the saved ones: " + "; ".join(differences[:5]))
        self.restore_system(state["system"])
        differences = self.system_differences(state["system"])
        if differences:
            raise RecoveryError("restored system values differ: " + "; ".join(differences))
        if self.app_reply("drop") != "OK":
            raise RecoveryError("restored and verified, but the app's copy could not be dropped; run --restore again")
        if installed and self.installed_sha() != installed["sha256"]:
            raise RecoveryError("settings restored, but the original build is no longer installed; run --restore again")
        os.unlink(STATE_FILE)
        if installed and os.path.exists(installed["apk"]):
            os.unlink(installed["apk"])
        return True

    # --- installed artifact: sessions that install other builds -----------------------------

    def _pull_installed(self, name):
        paths = [l.removeprefix("package:") for l in self.sh(f"pm path {PKG}").splitlines() if l.startswith("package:")]
        if len(paths) != 1:
            raise RecoveryError(f"expected one installed APK of {PKG}, found {paths or 'none'}")
        local = os.path.join(WORK, name)
        self.adb.run("pull", paths[0], local, timeout=120)
        return local

    def installed_sha(self):
        return file_sha256(self._pull_installed("installed-check.apk"))

    def save_installed(self, run):
        """A host copy of the installed APK and its hash, taken before anything changes."""
        local = self._pull_installed(f"installed-{run}.apk")
        return {"sha256": file_sha256(local), "apk": local}

    def apk_certificate(self, path):
        out = subprocess.run([os.path.join(_sdk_dir(), "build-tools", "34.0.0", "apksigner"), "verify", "--print-certs", path],
                             capture_output=True, text=True, env=dict(os.environ, JAVA_HOME=JAVA_HOME,
                             PATH=os.path.join(JAVA_HOME, "bin") + os.pathsep + os.environ.get("PATH", "")))
        m = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", out.stdout)
        if not m:
            raise RecoveryError(f"no signing certificate readable from {path}")
        return m.group(1)

    def install_apk(self, path):
        """An in-place update that keeps the app's data; never an uninstall."""
        self.adb.run("install", "-r", path, timeout=180)

    def restore_prerequisites(self):
        self.sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
        self.reconnect()

    def _saved_state(self):
        if not os.path.exists(STATE_FILE):
            raise RecoveryError("installing another build needs a saved transaction: begin first")
        with open(STATE_FILE) as f:
            state = json.load(f)
        if state.get("stage") != "saved" or not state.get("installed"):
            raise RecoveryError(f"the transaction is not ready for installs (stage {state.get('stage')!r})")
        return state

    def install(self, apk, observe=None):
        """Installs [apk] in place as one arm of a session. Signing must match the original build; the
        owner's settings copy in the app and the host's settings file must both survive the install.
        [observe] runs right after the install, before the prerequisites are restored, so an upgrade
        test sees the service exactly as the update left it."""
        state = self._saved_state()
        if self.apk_certificate(apk) != self.apk_certificate(state["installed"]["apk"]):
            raise RecoveryError(f"{apk} is signed differently from the installed build; never uninstall to work around it")
        sha = file_sha256(apk)
        state["arm"] = {"apk": os.path.abspath(apk), "sha256": sha}
        self._write_state(state)
        self.install_apk(apk)
        if self.installed_sha() != sha:
            raise RecoveryError(f"after installing {apk} a different build is installed")
        observed = observe() if observe else None
        self.restore_prerequisites()
        self.verify_recovery_copies(state)
        return observed

    def verify_recovery_copies(self, state):
        # Read-only on purpose: "snapshot" would replace a lost copy with the test settings now on disk.
        reply = self.app_reply("status")
        if reply != f"KEPT {state['run']} {state['count']}":
            raise RecoveryError(f"the app's settings copy did not survive the install ({reply!r})")
        with open(state["prefs_xml"]) as f:
            if len(parse_prefs(f.read())) != state["count"]:
                raise RecoveryError("the host's settings file no longer matches the saved count")

    def reinstall_original(self, installed):
        if not os.path.exists(installed["apk"]) or file_sha256(installed["apk"]) != installed["sha256"]:
            raise RecoveryError(f"the original build's copy {installed['apk']} is missing or changed; reinstall it manually")
        self.log("reinstalling the original build")
        self.install_apk(installed["apk"])
        if self.installed_sha() != installed["sha256"]:
            raise RecoveryError("reinstalling the original build did not take effect")
        self.restore_prerequisites()

    def _write_state(self, state):
        tmp = STATE_FILE + ".tmp"
        with open(tmp, "w") as f:
            json.dump(state, f, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, STATE_FILE)

    def system_differences(self, saved):
        now = self.snapshot_system()
        out = [f"{k}: saved {saved[k]!r} now {now[k]!r}" for k in ("system", "props", "zen", "mic", "volume")
               if saved[k] != now[k]]
        if any(abs(a - b) > 0.005 for a, b in zip(saved["brightness"], now["brightness"])):
            out.append(f"brightness: saved {saved['brightness']} now {now['brightness']}")
        return out

    def restore_prefs_file(self, xml):
        """Writes the saved settings file back while the app is stopped, so no live preference cache
        overwrites it, then starts the service again. Other accessibility services stay enabled."""
        enabled = [s for s in self.sh("settings get secure enabled_accessibility_services").split(":") if s not in ("", "null")]
        others = [s for s in enabled if s != SERVICE]
        self.sh(f"settings put secure enabled_accessibility_services {shlex.quote(':'.join(others) or 'null')}")
        self.sh(f"am force-stop {PKG}")
        self.eventually(lambda: not self.sh(f"pidof {PKG}"), 10, "the app did not stop for the settings restore")
        remote = "/data/local/tmp/bbh-owner-prefs.xml"
        self.adb.push_text(xml, remote)
        self.sh(f"chmod 644 {remote}")
        self.sh(f"run-as {PKG} cp {remote} shared_prefs/back_button_hero.xml")
        self.sh(f"rm {remote}")
        self.reconnect()

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

    def confirm_key(self):
        """The evdev code that confirms the app's prompts and switchers: Android's convention, the button
        reporting BUTTON_A (BTN_SOUTH). That is printed A (right) in Standard Style, printed B (bottom) in Xbox."""
        return self.face("b" if self.setting("flip_button_layout") == "1" else "a")

    def cancel_key(self):
        """The evdev code that cancels them: the button reporting BUTTON_B, printed B in Standard, A in Xbox."""
        return self.face("a" if self.setting("flip_button_layout") == "1" else "b")

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

    def task_ids(self, pkg):
        """Recent tasks of the stock test apps, whose task affinity is their package name."""
        pattern = rf"Task\{{\S+ #(\d+) [^}}]*?A=\d+:{re.escape(pkg)}(?:\s|}})"
        return re.findall(pattern, self.sh("dumpsys activity recents"))

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
