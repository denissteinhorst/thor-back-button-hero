"""A macro from the library plays its steps into the game, in order."""

import time

from dt.device import Fail
from dt.pad import Seq
from dt.runner import test

from .common import ensure_helper


@test("macro", "A bound macro presses its buttons, D-pad and stick into the game in order")
def plays(d):
    ensure_helper(d)
    # Printed labels, as the editor stores them: A, then D-pad right, then left stick up.
    d.put("macro_library", "m1\tDevice test\t1:A;120;80|D:R;120;80|LS:U;120;0")
    d.bind("face_y", "@macro", macro="m1")
    d.probe_start()
    try:
        d.clear_log()
        d.press(Seq().combo(d.face("y")))
        d.eventually(lambda: ("AXIS", "AXIS_Y", "-1") in d.probe_events(), 4,
                     f"the macro did not finish in the game: {d.probe_events()}")
        time.sleep(0.4)
        got = [e for e in d.probe_events() if e[0] == "AXIS" and e[2] != "0" or e[:2] == ("KEY", "DOWN")]
        # The face button keeps its print: in Xbox Style Android calls printed A BUTTON_B.
        key_a = "KEYCODE_BUTTON_B" if d.setting("flip_button_layout") == "1" else "KEYCODE_BUTTON_A"
        want = [("KEY", "DOWN", key_a), ("AXIS", "AXIS_HAT_X", "1"), ("AXIS", "AXIS_Y", "-1")]
        if got != want:
            raise Fail(f"expected {want}, the game got {got}")
    finally:
        d.probe_stop()
