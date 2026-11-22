#!/usr/bin/env python3
# SPDX-License-Identifier: 0BSD
"""Static contract checks for the behavior-preserving refactoring (refactoring plan, oracle catalog A).

  contract.py check [--phase S|R] [--only A1,A3]   compare fresh build outputs with the frozen fixtures
  contract.py snapshot --out DIR [--only NAMES]     write fixtures from the current outputs (a reviewed act)

contract.py builds nothing. It reads the outputs of `./tools/verify.sh` (or the matching Gradle tasks)
and refuses with BUILD REQUIRED when an output is older than an input it depends on, so an old build
can never pass for the current source. tools/contract/check.sh first lets Gradle update the outputs
incrementally and then relies on Gradle's content-based up-to-date check instead. Exit codes: 0 all PASS, 1 a FAIL, 2 BUILD REQUIRED or setup
error. Every check prints one PASS/FAIL line and, on FAIL, the difference.
"""

import argparse
import glob
import hashlib
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ElementTree
import zipfile

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
HERE = os.path.join(REPO, "tools", "contract")
PKG_PATH = "dev/steinhorst/backbuttonhero"
APP = os.path.join(REPO, "app")


def _path(name, default):
    """[default], unless CONTRACT_<NAME> points elsewhere: selftest.sh feeds deliberately broken copies."""
    return os.environ.get(f"CONTRACT_{name}", default)


FIXTURES = _path("FIXTURES", os.path.join(HERE, "baseline"))
LISTS = _path("LISTS", HERE)
RELEASE_APK = _path("RELEASE_APK", os.path.join(APP, "build/outputs/apk/release/app-release-unsigned.apk"))
DEBUG_APK = _path("DEBUG_APK", os.path.join(APP, "build/outputs/apk/debug/app-debug.apk"))
TEST_RESULTS = _path("TEST_RESULTS", os.path.join(APP, "build/test-results/testDebugUnitTest"))
JACOCO_XML = _path("JACOCO_XML", os.path.join(APP, "build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml"))
LINT_XML = _path("LINT_XML", os.path.join(APP, "build/reports/lint-results-debug.xml"))
GOLDENS = _path("GOLDENS", os.path.join(APP, "src/test/resources/golden"))
STORAGE = _path("STORAGE", os.path.join(APP, "src/test/resources/storage"))
COMMANDS = _path("COMMANDS", os.path.join(APP, "src/test/resources/commands"))
BUILD_FILE = _path("BUILD_FILE", os.path.join(APP, "build.gradle.kts"))
LINT_BASELINE = _path("LINT_BASELINE", os.path.join(APP, "lint-baseline.xml"))
SWITCHER_SERVICE = _path("SWITCHER_SERVICE", os.path.join(APP, f"src/main/java/{PKG_PATH}/SwitcherService.kt"))
JAVA_HOME = "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"

# Build inputs per output. An output older than any of these files is stale.
BUILD_INPUTS = ["app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
                "gradle/wrapper/gradle-wrapper.properties", "app/lint-baseline.xml"]
INPUTS = {
    RELEASE_APK: ["app/src/main"],
    DEBUG_APK: ["app/src/main", "app/src/debug"],
    TEST_RESULTS: ["app/src/main", "app/src/test"],
    JACOCO_XML: ["app/src/main", "app/src/test"],
    LINT_XML: ["app/src/main", "app/src/debug", "app/src/test", "app/src/androidTest"],
}
# check.sh sets CONTRACT_GRADLE_FRESH after Gradle has brought every output up to date by content.
# Overridden outputs are selftest copies whose age says nothing, unless CONTRACT_FORCE_FRESHNESS asks.
CHECK_FRESHNESS = not os.environ.get("CONTRACT_GRADLE_FRESH") and (os.environ.get("CONTRACT_FORCE_FRESHNESS") or not any(
    k.startswith("CONTRACT_") and k not in ("CONTRACT_PHASE", "CONTRACT_FIXTURES", "CONTRACT_LISTS", "CONTRACT_GRADLE_FRESH")
    and os.environ[k] for k in os.environ))

# The one release entry that legitimately changes with every commit: AGP records the Git revision.
VOLATILE_ENTRY = "META-INF/version-control-info.textproto"


class BuildRequired(Exception):
    pass


def sdk_dir():
    props = os.path.join(REPO, "local.properties")
    if os.path.exists(props):
        for line in open(props):
            if line.startswith("sdk.dir="):
                return line.split("=", 1)[1].strip()
    return os.environ.get("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools")


def tool(name):
    hits = sorted(glob.glob(os.path.join(sdk_dir(), "build-tools", "34.0.0", name)))
    if not hits:
        raise BuildRequired(f"SDK tool {name} not found under build-tools/34.0.0")
    return hits[-1]


def run(*cmd, env=None):
    res = subprocess.run(cmd, capture_output=True, text=True, env=env)
    if res.returncode:
        raise BuildRequired(f"{' '.join(cmd[:3])} failed: {res.stderr.strip()[:300]}")
    return res.stdout


def input_files(roots):
    out = subprocess.run(["git", "-C", REPO, "ls-files", "--cached", "--others", "--exclude-standard", "--", *roots],
                         capture_output=True, text=True).stdout.split()
    return [os.path.join(REPO, p) for p in out if os.path.exists(os.path.join(REPO, p))]


def require_fresh(output):
    """Raises BuildRequired unless [output] exists and is newer than every input it depends on."""
    if not os.path.exists(output):
        raise BuildRequired(f"missing {os.path.relpath(output, REPO)}; run ./tools/verify.sh")
    stamp = os.path.getmtime(output)
    if os.path.isdir(output):
        files = glob.glob(os.path.join(output, "*.xml"))
        if not files:
            raise BuildRequired(f"no results in {os.path.relpath(output, REPO)}")
        stamp = min(os.path.getmtime(f) for f in files)
    if not CHECK_FRESHNESS:
        return
    newer = [f for f in input_files(INPUTS[output] + BUILD_INPUTS) if os.path.getmtime(f) > stamp]
    if newer:
        raise BuildRequired(f"{os.path.relpath(output, REPO)} is older than {os.path.relpath(newer[0], REPO)}"
                            f" (+{len(newer) - 1} more); run ./tools/verify.sh")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_fixture(name):
    path = os.path.join(FIXTURES, name)
    if not os.path.exists(path):
        raise BuildRequired(f"fixture {os.path.relpath(path, REPO)} is missing")
    return open(path).read()


def lines(text):
    return [l for l in text.splitlines() if l.strip() and not l.startswith("#")]


def diff(expected, actual, limit=20):
    """The lines only in one side, prefixed - (fixture) and + (now)."""
    exp, act = set(expected), set(actual)
    out = [f"- {l}" for l in expected if l not in act] + [f"+ {l}" for l in actual if l not in exp]
    return out[:limit] + ([f"… {len(out) - limit} more"] if len(out) > limit else [])


def renames():
    """Reviewed old -> new names (types or Class#method), empty until a move needs one."""
    path = os.path.join(LISTS, "renames.txt")
    mapping = {}
    for line in lines(open(path).read()) if os.path.exists(path) else []:
        old, sep, new = line.partition("->")
        if not sep:
            raise BuildRequired(f"renames.txt: malformed line {line!r}")
        mapping[old.strip()] = new.strip()
    return mapping


def renamed(name, mapping):
    if name in mapping:
        return mapping[name]
    cls, sep, method = name.partition("#")
    return (mapping.get(cls, cls) + sep + method) if sep else name


# --- artifact readers -------------------------------------------------------------------------

def manifest(apk):
    return run(tool("aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", apk)


def resources(apk):
    return run(tool("aapt2"), "dump", "resources", apk)


def entries(apk):
    """Every non-DEX entry with its CRC; the volatile Git revision is normalized and classified."""
    out = []
    with zipfile.ZipFile(apk) as z:
        for info in z.infolist():
            if re.fullmatch(r"classes\d*\.dex", info.filename):
                continue
            if info.filename == VOLATILE_ENTRY:
                text = re.sub(r'revision: "[0-9a-f]+"', 'revision: "<git>"', z.read(info).decode())
                out.append(f"{info.filename} normalized-sha256={sha256(text.encode())}")
            else:
                out.append(f"{info.filename} crc={info.CRC:08x} size={info.file_size}")
    return out


def dex_bytes(apk):
    with zipfile.ZipFile(apk) as z:
        return [z.read(n) for n in sorted(z.namelist()) if re.fullmatch(r"classes\d*\.dex", n)]


def dex_classes(apk):
    """Class descriptor -> its direct methods as (name, type, access text)."""
    text = run(tool("dexdump"), apk)
    classes = {}
    for block in re.split(r"\nClass #\d+\s+-\n", text)[1:]:
        m = re.search(r"Class descriptor\s+:\s+'([^']+)'", block)
        if not m:
            continue
        direct = block.split("Direct methods", 1)[1].split("Virtual methods", 1)[0] if "Direct methods" in block else ""
        methods = re.findall(r"name\s+:\s+'([^']+)'\s+type\s+:\s+'([^']+)'\s+access\s+:\s+0x[0-9a-f]+ \(([^)]*)\)", direct)
        classes[m.group(1)] = methods
    return classes


def descriptor(name):
    return f"L{PKG_PATH}/{name.replace('.', '/')};"


# --- current state of each contract (the same functions write the fixtures) -------------------

def now_manifest_release():
    require_fresh(RELEASE_APK)
    return manifest(RELEASE_APK)


def now_manifest_debug():
    require_fresh(DEBUG_APK)
    return manifest(DEBUG_APK)


def now_resources_release():
    require_fresh(RELEASE_APK)
    return resources(RELEASE_APK)


def now_entries_release():
    require_fresh(RELEASE_APK)
    return "\n".join(entries(RELEASE_APK)) + "\n"


def now_dex_release():
    require_fresh(RELEASE_APK)
    return "".join(f"{sha256(d)}\n" for d in dex_bytes(RELEASE_APK))


def now_goldens():
    return "".join(f"{sha256(open(p, 'rb').read())}  {os.path.basename(p)}\n"
                   for p in sorted(glob.glob(os.path.join(GOLDENS, "*.txt"))))


def now_storage():
    return "".join(f"{sha256(open(p, 'rb').read())}  {os.path.basename(p)}\n"
                   for p in sorted(glob.glob(os.path.join(STORAGE, "*"))))


def now_commands():
    return "".join(f"{sha256(open(p, 'rb').read())}  {os.path.basename(p)}\n"
                   for p in sorted(glob.glob(os.path.join(COMMANDS, "*"))))


def test_cases():
    require_fresh(TEST_RESULTS)
    cases, failed = [], []
    for path in sorted(glob.glob(os.path.join(TEST_RESULTS, "*.xml"))):
        for case in ElementTree.parse(path).getroot().iter("testcase"):
            name = f"{case.get('classname').rsplit('.', 1)[-1]}#{case.get('name')}"
            cases.append(name)
            if case.find("failure") is not None or case.find("error") is not None or case.find("skipped") is not None:
                failed.append(name)
    return sorted(cases), failed


def now_tests():
    return "".join(f"{c}\n" for c in test_cases()[0])


def ratchet_rules():
    """Selector -> minimum from the coverage rules in app/build.gradle.kts."""
    text = open(BUILD_FILE).read()
    rules = {}
    for sel, minimum in re.findall(r'includes = listOf\("dev\.steinhorst\.backbuttonhero\.([^"]+)"\)\s*'
                                   r'(?:limit \{[^}]*?)?minimum = "([0-9.]+)"', text, re.S):
        rules[sel] = minimum
    block = text[text.index("mapOf(", text.index("jacocoCoverageVerification")):]
    block = block[:block.index(").forEach")]
    for sel, minimum in re.findall(r'"([A-Za-z\\$*]+)" to "([0-9.]+)"', block):
        rules[sel.replace("\\$", "$")] = minimum
    if len(rules) != len(re.findall(r'"[^"]+" to "[0-9.]+"', block)) + len(re.findall(r"includes = listOf", text)) - 1:
        raise BuildRequired("app/build.gradle.kts: a coverage rule could not be parsed")
    return rules


def now_ratchets():
    return "".join(f"{k} {v}\n" for k, v in sorted(ratchet_rules().items()))


def lint_issues():
    require_fresh(LINT_XML)
    root = ElementTree.parse(LINT_XML).getroot()
    return sorted(f"{i.get('id')}: {i.get('message')}" for i in root.iter("issue"))


def now_lint():
    return f"lint-baseline.xml sha256={sha256(open(LINT_BASELINE, 'rb').read())}\n" + "".join(f"{l}\n" for l in lint_issues())


def classpath_tree():
    env = dict(os.environ, JAVA_HOME=os.environ.get("JAVA_HOME") if os.path.isdir(os.environ.get("JAVA_HOME", "")) else JAVA_HOME)
    out = run(os.path.join(REPO, "gradlew"), "-q", ":app:dependencies", "--configuration", "releaseRuntimeClasspath",
              "--console=plain", env=env)
    return "".join(l + "\n" for l in out.splitlines() if re.match(r"^[\\+| ]*[\\+]--- ", l))


SNAPSHOTS = {
    "manifest-release.txt": now_manifest_release,
    "manifest-debug.txt": now_manifest_debug,
    "resources-release.txt": now_resources_release,
    "entries-release.txt": now_entries_release,
    "dex-release.sha256": now_dex_release,
    "goldens.sha256": now_goldens,
    "storage.sha256": now_storage,
    "commands.sha256": now_commands,
    "tests.txt": now_tests,
    "ratchets.txt": now_ratchets,
    "lint-issues.txt": now_lint,
    "classpath-release.txt": classpath_tree,
}


# --- the checks -------------------------------------------------------------------------------

def compare(fixture, current):
    exp, act = lines(read_fixture(fixture)), lines(current)
    return [] if exp == act else (diff(exp, act) or ["same lines, different order"])


def a0():
    """Release DEX identical to the baseline: production code unchanged (S-phases only)."""
    return compare("dex-release.sha256", now_dex_release())


def a1():
    return [f"release: {d}" for d in compare("manifest-release.txt", now_manifest_release())] + \
           [f"debug: {d}" for d in compare("manifest-debug.txt", now_manifest_debug())]


def a2():
    return compare("resources-release.txt", now_resources_release())


def a3():
    return compare("entries-release.txt", now_entries_release())


def a4():
    """Frozen components exist; root entry points keep `public static void main(String[])`."""
    require_fresh(RELEASE_APK)
    require_fresh(DEBUG_APK)
    release, debug = dex_classes(RELEASE_APK), dex_classes(DEBUG_APK)
    problems = []
    for line in lines(open(os.path.join(LISTS, "classes.txt")).read()):
        kind, name = line.split()
        found = (debug if kind == "debug" else release).get(descriptor(name))
        if found is None:
            problems.append(f"{kind} class {name} missing")
        elif kind == "root" and not any(n == "main" and t == "([Ljava/lang/String;)V" and "PUBLIC" in a and "STATIC" in a
                                        for n, t, a in found):
            problems.append(f"root entry point {name} has no public static main(String[])")
    return problems


def dex_string_item(value):
    length, prefix = len(value.encode("utf-16-le")) // 2, bytearray()
    while True:
        byte, length = length & 0x7F, length >> 7
        prefix.append(byte | (0x80 if length else 0))
        if not length:
            return bytes(prefix) + value.encode() + b"\0"


def a5():
    """Wire strings are present in the DEX string data (supporting evidence; encode/read tests in S3)."""
    require_fresh(RELEASE_APK)
    require_fresh(DEBUG_APK)
    blobs = {"release": b"".join(dex_bytes(RELEASE_APK)), "debug": b"".join(dex_bytes(DEBUG_APK))}
    problems = []
    for line in lines(open(os.path.join(LISTS, "wire-strings.txt")).read()):
        kind, _, value = line.partition("\t")
        # A DEX string_data_item is its UTF-16 length (ULEB128), the MUTF-8 bytes and a NUL: matching
        # the whole item keeps a renamed superstring ("X_MOVED") from passing for the frozen "X".
        if dex_string_item(value) not in blobs[kind]:
            problems.append(f"{kind} DEX lacks {value!r}")
    return problems


def a6():
    return compare("goldens.sha256", now_goldens()) + compare("storage.sha256", now_storage()) + \
        compare("commands.sha256", now_commands())


def a7():
    """Every frozen test case still exists (or is mapped in renames.txt) and every case passed."""
    cases, failed = test_cases()
    mapping = renames()
    current = set(cases)
    missing = [c for c in lines(read_fixture("tests.txt")) if renamed(c, mapping) not in current]
    return [f"missing test {c}" for c in missing[:20]] + [f"not passing: {c}" for c in failed[:20]] + \
           ([f"… {len(missing) + len(failed) - 40} more"] if len(missing) + len(failed) > 40 else [])


def a8():
    """No ratchet removed or lowered; every selector matches compiled classes in the coverage report."""
    require_fresh(JACOCO_XML)
    mapping = renames()
    rules = ratchet_rules()
    problems = []
    for line in lines(read_fixture("ratchets.txt")):
        sel, minimum = line.split()
        new = renamed(sel, mapping)
        if new not in rules:
            problems.append(f"ratchet {sel} removed")
        elif float(rules[new]) < float(minimum):
            problems.append(f"ratchet {new} lowered {minimum} -> {rules[new]}")
    classes = [c.get("name") for c in ElementTree.parse(JACOCO_XML).getroot().iter("class")]
    for sel in rules:
        pattern = re.compile(re.escape(f"{PKG_PATH}/{sel}").replace(r"\*", "[^/]*") + "$")
        if not any(pattern.match(c) for c in classes):
            problems.append(f"ratchet {sel} matches no compiled class")
    return problems


def a9():
    return compare("classpath-release.txt", classpath_tree())


def a10():
    text = open(SWITCHER_SERVICE).read()
    return [] if re.search(r"const val DEBUG_GESTURES = false\b", text) and "DEBUG_GESTURES = true" not in text \
        else ["DEBUG_GESTURES is not false in SwitcherService.kt"]


def a11():
    return compare("lint-issues.txt", now_lint())


CHECKS = [("A0", "release DEX unchanged (S-phases)", a0), ("A1", "manifests", a1), ("A2", "resource table", a2),
          ("A3", "non-DEX APK entries", a3), ("A4", "frozen classes and root entry points", a4),
          ("A5", "wire strings", a5), ("A6", "goldens, stored-data and command fixtures", a6), ("A7", "test contract", a7),
          ("A8", "coverage ratchets", a8), ("A9", "release classpath", a9), ("A10", "DEBUG_GESTURES off", a10),
          ("A11", "lint identities", a11)]


def check(phase, only):
    selected = [c for c in CHECKS if (not only or c[0] in only) and not (c[0] == "A0" and phase != "S")]
    worst = 0
    for cid, title, fn in selected:
        try:
            problems = fn()
        except BuildRequired as e:
            print(f"BUILD REQUIRED {cid} {title}: {e}")
            worst = 2
            continue
        print(f"{'PASS' if not problems else 'FAIL'} {cid} {title}")
        for p in problems:
            print(f"    {p}")
        if problems:
            worst = max(worst, 1)
    if phase != "S" and (not only or "A0" in only):
        print("SKIP A0 release DEX unchanged: R-phases change code by design")
    return worst


def snapshot(out, only):
    os.makedirs(out, exist_ok=True)
    for name, fn in SNAPSHOTS.items():
        if only and name not in only:
            continue
        with open(os.path.join(out, name), "w") as f:
            f.write(fn())
        print(f"wrote {os.path.relpath(os.path.join(out, name), REPO)}")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("check")
    c.add_argument("--phase", choices=("S", "R"), default=os.environ.get("CONTRACT_PHASE", "S"))
    c.add_argument("--only", default="")
    s = sub.add_parser("snapshot")
    s.add_argument("--out", required=True)
    s.add_argument("--only", default="")
    args = parser.parse_args(argv)
    only = {o.strip() for o in args.only.split(",") if o.strip()}
    try:
        return check(args.phase, only) if args.cmd == "check" else snapshot(args.out, only)
    except BuildRequired as e:
        print(f"BUILD REQUIRED: {e}")
        return 2


if __name__ == "__main__":
    sys.exit(main())
