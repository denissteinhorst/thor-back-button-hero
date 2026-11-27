"""The test areas, in the order a full run takes them.

The order keeps related checks together and saves set-up: the settings screen and quiet setting switches first, then the
input paths (every hotkey source against one observable action), Back's own gestures and the on/off
shortcut, then everything that moves apps and windows, and last the checks that disturb the device
the most (controller style, screens and standby).
"""

from . import preflight, settings, ayn, levels, media, sources, gestures, toggle, windows, appcontrol, capture, macro, gyro, panels, exceptions, style, confirm, frontends  # noqa: F401
