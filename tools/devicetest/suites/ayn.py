"""AYN's quick-setting tiles and Android's switches as hotkeys: each must change the very setting the
system tile shows and announce the new state."""

from dt.device import Fail
from dt.pad import L1
from dt.runner import test

from .common import combo, ensure_helper, on_off


def _toggle(d, action, title_res, read, is_on):
    ensure_helper(d)
    d.bind("l1", action)
    start = read()
    for _ in range(2):
        before = read()
        combo(d, L1)
        now = d.eventually(lambda: (lambda v: v if v != before else None)(read()), 4, f"{action}: value stayed {before}")
        d.expect_toast(d.text("ayn_notice_value", d.text(title_res), on_off(d, is_on(now))))
    if read() != start:
        raise Fail(f"{action}: two presses did not return to {start}")


def _system(d, key):
    return lambda: d.setting(key)


def _bit(v):
    return v not in ("0", "null", "")


@test("ayn", "Vibration toggles vibrate_on")
def vibration(d):
    _toggle(d, "@vibration", "ayn_vibration", _system(d, "vibrate_on"), _bit)


@test("ayn", "Direct Power Supply toggles charging separation")
def direct_power(d):
    _toggle(d, "@direct_power", "ayn_direct_power", _system(d, "is_charging_separation"), _bit)


@test("ayn", "Force Landscape toggles force_landscape")
def force_landscape(d):
    _toggle(d, "@force_landscape", "ayn_force_landscape", _system(d, "force_landscape"), _bit)


@test("ayn", "Virtual Mouse toggles AYN's mouse switch")
def virtual_mouse(d):
    _toggle(d, "@virtual_mouse", "ayn_virtual_mouse", _system(d, "global_gamepad_to_mouse_mode"), _bit)


@test("ayn", "Do Not Disturb toggles zen mode (priority)")
def dnd(d):
    _toggle(d, "@do_not_disturb", "ayn_do_not_disturb", lambda: d.setting("zen_mode", "global"), _bit)


@test("ayn", "Mute Microphone toggles Android's microphone privacy")
def microphone(d):
    _toggle(d, "@mute_microphone", "ayn_mute_microphone", lambda: "1" if d.microphone_blocked() else "0", _bit)


def _options(d, action, title_res, cases, read):
    ensure_helper(d)
    for option, label_res, expected in cases:
        d.bind("l1", action, ayn_option=option)
        combo(d, L1)
        d.eventually(lambda: read() == expected, 4, f"{action} {option}: expected {expected}, got {read()}")
        d.expect_toast(d.text("ayn_notice_value", d.text(title_res), d.text(label_res)))


@test("ayn", "Performance Mode applies High, Medium and Standard")
def performance(d):
    _options(d, "@performance", "ayn_performance", [
        ("high", "ayn_performance_high", "2"), ("medium", "ayn_performance_medium", "1"),
        ("standard", "ayn_performance_standard", "0"),
    ], _system(d, "performance_mode"))


@test("ayn", "Fan Control applies Off, Quiet, Sports and Smart")
def fan(d):
    _options(d, "@fan", "ayn_fan", [
        ("off", "ayn_fan_off", "0"), ("quiet", "ayn_fan_quiet", "1"), ("sports", "ayn_fan_sports", "5"),
        ("smart", "ayn_fan_smart", "4"),
    ], _system(d, "fan_mode"))


@test("ayn", "Refresh Rate sets 120 Hz, 60 Hz and toggles")
def refresh(d):
    rates = lambda: (d.setting("min_refresh_rate"), d.setting("peak_refresh_rate"))  # noqa: E731
    _options(d, "@refresh_rate", "ayn_refresh_rate", [
        ("120", "ayn_refresh_120", ("120.0", "120.0")), ("60", "ayn_refresh_60", ("60.0", "60.0")),
    ], rates)
    d.bind("l1", "@refresh_rate", ayn_option="toggle")
    for hz in (120, 60):
        combo(d, L1)
        d.eventually(lambda: rates() == (f"{hz}.0", f"{hz}.0"), 4, f"toggle did not reach {hz} Hz: {rates()}")
        d.expect_toast(d.text("ayn_notice_value", d.text("ayn_refresh_rate"), d.text("ayn_refresh_hz", hz)))


@test("ayn", "Stick Lighting switches both, left and right")
def stick_light(d):
    ensure_helper(d)
    d.root("settings put system joystick_light_enabled 0,0")
    read = _system(d, "joystick_light_enabled")
    for option, label, expected, on in [
        ("both", "ayn_sticks_both", "1,1", True), ("left", "ayn_sticks_left", "0,1", False),
        ("right", "ayn_sticks_right", "0,0", False), ("right", "ayn_sticks_right", "0,1", True),
        ("both", "ayn_sticks_both", "0,0", False),
    ]:
        d.bind("l1", "@stick_light", ayn_option=option)
        combo(d, L1)
        d.eventually(lambda: read() == expected, 4, f"lights {option}: expected {expected}, got {read()}")
        d.expect_toast(d.text("ayn_notice_part", d.text("ayn_stick_light"), d.text(label), on_off(d, on)))
