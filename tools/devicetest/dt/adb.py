"""adb access, root through AYN's pservice, and the two small helper dex files the harness pushes."""

import hashlib
import os
import shlex
import subprocess
import tempfile
import time

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
WORK = os.path.join(REPO, "app", "build", "devicetest")
REMOTE = "/data/local/tmp/bbh-dt"
JAVA_HOME = "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"


class AdbError(RuntimeError):
    pass


def _sdk_dir():
    props = os.path.join(REPO, "local.properties")
    if os.path.exists(props):
        for line in open(props):
            if line.startswith("sdk.dir="):
                return line.split("=", 1)[1].strip()
    return os.environ.get("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools")


class Adb:
    def __init__(self, serial=None):
        self.serial = serial or self._only_device()
        os.makedirs(WORK, exist_ok=True)

    @staticmethod
    def _only_device():
        out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
        devices = [l.split()[0] for l in out.splitlines()[1:] if l.strip().endswith("device")]
        if len(devices) != 1:
            raise AdbError(f"expected exactly one adb device, found {devices or 'none'}")
        return devices[0]

    def run(self, *args, check=True, timeout=60):
        res = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, text=True, timeout=timeout)
        if check and res.returncode != 0:
            raise AdbError(f"adb {' '.join(args)} failed: {res.stderr.strip() or res.stdout.strip()}")
        return res.stdout

    def sh(self, command, check=False, timeout=60):
        return self.run("shell", command, check=check, timeout=timeout).replace("\r", "").rstrip("\n")

    def push_text(self, text, remote):
        with tempfile.NamedTemporaryFile("w", delete=False, suffix=".sh") as f:
            f.write(text)
            path = f.name
        try:
            self.run("push", path, remote)
        finally:
            os.unlink(path)

    # --- root -------------------------------------------------------------------------------

    def ensure_tools(self):
        """Builds the helper dex files when their sources changed and pushes them when missing."""
        self.sh(f"mkdir -p {REMOTE}")
        src = os.path.join(REPO, "tools", "devicetest", "java")
        sources = sorted(os.path.join(src, n) for n in os.listdir(src) if n.endswith(".java"))
        digest = hashlib.sha256(b"".join(open(p, "rb").read() for p in sources)).hexdigest()[:16]
        dex = os.path.join(WORK, f"tools-{digest}.dex")
        if not os.path.exists(dex):
            self._build_dex(sources, dex)
        remote = f"{REMOTE}/tools-{digest}.dex"
        if self.sh(f"[ -f {remote} ] && echo yes") != "yes":
            self.run("push", dex, remote)
        self.dex = remote
        if self.root_line("id").find("uid=0") < 0:
            raise AdbError("pservice root is not available (SELinux enforcing?)")

    def _build_dex(self, sources, dex):
        sdk = _sdk_dir()
        android_jar = os.path.join(sdk, "platforms", "android-34", "android.jar")
        d8 = os.path.join(sdk, "build-tools", "34.0.0", "d8")
        env = dict(os.environ, JAVA_HOME=JAVA_HOME, PATH=f"{JAVA_HOME}/bin:{os.environ.get('PATH', '')}")
        with tempfile.TemporaryDirectory() as classes:
            subprocess.run([f"{JAVA_HOME}/bin/javac", "--release", "8", "-nowarn", "-cp", android_jar, "-d", classes, *sources],
                           check=True, env=env, capture_output=True)
            class_files = [os.path.join(classes, n) for n in os.listdir(classes) if n.endswith(".class")]
            with tempfile.TemporaryDirectory() as out:
                subprocess.run([d8, "--min-api", "30", "--lib", android_jar, "--output", out, *class_files],
                               check=True, env=env, capture_output=True)
                os.replace(os.path.join(out, "classes.dex"), dex)

    def root_line(self, command):
        """One root command; only its first output line comes back (pservice's limit)."""
        if len(command) > 280:
            raise AdbError("root command too long, use root_script")
        return self.sh(f"CLASSPATH={self.dex} app_process /system/bin RootExec {shlex.quote(command)}")

    def root_script(self, body, timeout=120):
        """Runs a shell script as root and returns its complete output."""
        script = f"{REMOTE}/run.sh"
        out = f"{REMOTE}/out.txt"
        self.push_text(body, script)
        self.sh(f"rm -f {out}")
        self.root_line(f"sh {script} > {out} 2>&1; chmod 644 {out}")
        deadline = time.time() + timeout
        while self.sh(f"[ -f {out} ] && echo yes") != "yes":
            if time.time() > deadline:
                raise AdbError("root script timed out")
            time.sleep(0.1)
        return self.sh(f"cat {out}")

    def probe(self, *args):
        return self.root_line(f"CLASSPATH={self.dex} app_process /system/bin DisplayProbe {' '.join(args)}")
