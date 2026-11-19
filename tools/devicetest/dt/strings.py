"""The app's own string resources, so expected toast texts follow the device's language."""

import os
import re
import xml.etree.ElementTree as ET

from .adb import REPO

_FORMAT = re.compile(r"%(?:(\d+)\$)?([-#+ 0,(]*\d*(?:\.\d+)?)([sdf%])")


class Strings:
    def __init__(self, locale):
        self.german = locale.lower().startswith("de")
        base = os.path.join(REPO, "app", "src", "main", "res")
        self.values = self._load(os.path.join(base, "values", "strings.xml"))
        if self.german:
            self.values.update(self._load(os.path.join(base, "values-de", "strings.xml")))

    @staticmethod
    def _load(path):
        out = {}
        for node in ET.parse(path).getroot().iter("string"):
            text = "".join(node.itertext())
            out[node.get("name")] = text.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n")
        return out

    def __call__(self, name, *args):
        text = self.values[name]
        if not args:
            return text.replace("%%", "%")

        def sub(m):
            if m.group(3) == "%":
                return "%"
            index = int(m.group(1)) - 1 if m.group(1) else 0
            value = ("%" + m.group(2) + m.group(3)) % args[index]
            return value.replace(".", ",") if self.german and m.group(3) == "f" else value

        return _FORMAT.sub(sub, text)
