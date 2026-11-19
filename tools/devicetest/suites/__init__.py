"""The test areas, in the order a full run takes them.

The order keeps related checks together and saves set-up: quiet setting switches first, then the
input paths (every hotkey source against one observable action), Back's own gestures and the on/off
shortcut, then everything that moves apps and windows, and last the checks that disturb the device
the most (controller style, screens and standby).
"""

from . import preflight, ayn, levels, media, sources, gestures, toggle, windows, appcontrol, capture, macro, panels, exceptions, style  # noqa: F401
