package dev.steinhorst.backbuttonhero

/**
 * Safety for everything that ends up in a root shell ([PService]). Package names are read from
 * other apps' accessibility nodes, so an app controls its own name - validate, then quote.
 */
object Shell {
    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /** A real Android package name, nothing a shell could interpret. */
    fun isPkg(s: String?): Boolean = s != null && s.length <= 255 && PKG.matches(s)

    /** Single-quotes [s] for sh, whatever its content. */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
