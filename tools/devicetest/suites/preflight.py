from dt.device import Fail
from dt.runner import test

from .common import ensure_helper


@test("preflight", "service bound, root reachable, input helper running")
def preflight(d):
    d.eventually(d.service_bound, 10, "the accessibility service is not bound")
    if "uid=0" not in d.root("id"):
        raise Fail("pservice root is unavailable")
    ensure_helper(d)
    d.log(f"    display top={d.top} bottom={d.bottom}, locale {d.locale}, helper pid {d.helper_pid()}")
