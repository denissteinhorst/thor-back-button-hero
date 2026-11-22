"""Builds root shell scripts that press the Thor's controller like a finger would.

Events are written into AYN's pad device: while the root input helper runs, through the helper's
own open file descriptor of the (unlinked) node, so they take exactly the path of real presses;
without the helper straight into the node. Codes are Linux evdev codes as AYN's pad reports them.
"""

KEY = 1
ABS = 3

BACK = 158
HOME = 102
A = 304
B = 305
X = 307
Y = 308
L1 = 310
R1 = 311
L2 = 312
R2 = 313
SELECT = 314
START = 315
L3 = 317
R3 = 318

ABS_X, ABS_Y, ABS_Z, ABS_RZ = 0, 1, 2, 5
ABS_GAS, ABS_BRAKE = 9, 10
HAT_X, HAT_Y = 16, 17
STICK_MAX = 32767

# Which trigger axis travels with the trigger's key, as on the real pad.
TRIGGER_AXIS = {L2: ABS_BRAKE, R2: ABS_GAS}

HEADER = r"""
p=$(pidof libpadhelper.so)
f=
if [ -n "$p" ]; then
  for d in /proc/$p/fd/*; do case "$(readlink $d)" in /dev/input/event*deleted*) f=$d;; esac; done
fi
if [ -z "$f" ]; then
  # The first handler field reads "Handlers=eventN", so match the event name anywhere in a field.
  n=$(awk '/^N: Name="Xbox Wireless Controller"/{x=1} x&&/^H: Handlers=/{for(i=2;i<=NF;i++) if(match($i, /event[0-9]+/)){print substr($i, RSTART, RLENGTH); exit}}' /proc/bus/input/devices)
  [ -n "$n" ] && f=/dev/input/$n
fi
[ -c "$f" ] || { echo "ERR no pad"; exit 1; }
e() { sendevent "$f" $1 $2 $3; }
s() { sendevent "$f" 0 0 0; }
"""


# The modifier the layer under test is pressed with: Back, or Home in dual mode (see runner.LAYERS).
modifier = BACK


class Seq:
    """A timed sequence of pad events; [script] turns it into a root shell script."""

    def __init__(self):
        self.lines = []

    def raw(self, line):
        self.lines.append(line)
        return self

    def wait(self, ms):
        if ms > 0:
            self.lines.append(f"sleep {ms / 1000:.3f}")
        return self

    def key(self, code, down):
        events = [f"e {KEY} {code} {1 if down else 0}"]
        axis = TRIGGER_AXIS.get(code)
        if axis is not None:
            events.append(f"e {ABS} {axis} {STICK_MAX if down else 0}")
        self.lines.append("; ".join(events) + "; s")
        return self

    def abs(self, code, value):
        self.lines.append(f"e {ABS} {code} {value}; s")
        return self

    def tap(self, code, ms=90, after=150):
        return self.key(code, True).wait(ms).key(code, False).wait(after)

    def hat(self, direction, ms=120, after=150):
        axis, value = {"up": (HAT_Y, -1), "down": (HAT_Y, 1), "left": (HAT_X, -1), "right": (HAT_X, 1)}[direction]
        return self.abs(axis, value).wait(ms).abs(axis, 0).wait(after)

    def stick(self, side, direction, ms=150, after=150, amount=30000):
        x, y = (ABS_X, ABS_Y) if side == "left" else (ABS_Z, ABS_RZ)
        axis, value = {"up": (y, -amount), "down": (y, amount), "left": (x, -amount), "right": (x, amount)}[direction]
        return self.abs(axis, value).wait(ms).abs(axis, 0).wait(after)

    def with_back(self, inner, lead=250, trail=200):
        """The layer's modifier (Back, or Home in dual mode) held around [inner], the way every shortcut is pressed."""
        self.key(modifier, True).wait(lead)
        inner(self)
        return self.wait(trail).key(modifier, False)

    def combo(self, code, lead=250, trail=200):
        return self.with_back(lambda s: s.tap(code, after=0), lead, trail)

    def script(self):
        body = [HEADER, *self.lines, "echo DONE"]
        return "\n".join(body) + "\n"
