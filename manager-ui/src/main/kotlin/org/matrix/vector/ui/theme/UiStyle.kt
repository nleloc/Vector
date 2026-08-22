package org.matrix.vector.ui.theme

/** Which design system the app renders with.
  * Persisted under the string keys used here. */
enum class UiStyle(val key: String) {
    Material("material"),
    Miuix("miuix");

    companion object {
        fun from(key: String?): UiStyle = entries.firstOrNull { it.key == key } ?: Material
    }
}
