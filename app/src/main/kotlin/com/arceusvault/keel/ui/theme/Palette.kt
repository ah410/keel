package com.arceusvault.keel.ui.theme

import androidx.compose.ui.graphics.Color

/** One Keel color theme: deep harbor blues with a sea-glass teal and an amber accent. */
data class Palette(
    val name: String,
    val label: String,
    val dark: Boolean,
    val bg1: Color,
    val bg2: Color,
    val fg1: Color,
    val fg2: Color,
    val altBg1: Color,
    val altBg2: Color,
    val topbar: Color,
    val topbarText: Color,
    val primary: Color,
    val accent: Color,
    val blue: Color,
    val green: Color,
    val yellow: Color,
    val orange: Color,
    val red: Color,
    val cyan: Color,
    val magenta: Color,
    val violet: Color,
) {
    /** Muted text for labels and secondary lines. */
    val muted: Color get() = fg2.copy(alpha = if (dark) 0.7f else 0.85f)

    /** Divider and track color. */
    val lines: Color get() = altBg1

    companion object {
        /** Follows the phone's light or dark setting. */
        const val SYSTEM = "system"

        val Dark = Palette(
            "dark", "Dark", true,
            bg1 = Color(0xFF0E161C), bg2 = Color(0xFF16212A), fg1 = Color(0xFFE7EEF2), fg2 = Color(0xFFC3D0D8),
            altBg1 = Color(0xFF233340), altBg2 = Color(0xFF33495A),
            topbar = Color(0xFF0E161C), topbarText = Color(0xFFE7EEF2),
            primary = Color(0xFF34C3B0), accent = Color(0xFFF4A259),
            blue = Color(0xFF5AA9E6), green = Color(0xFF5CC98A), yellow = Color(0xFFE9C46A), orange = Color(0xFFF4A259),
            red = Color(0xFFEF6461), cyan = Color(0xFF4FD1D9), magenta = Color(0xFFD16BA5), violet = Color(0xFF9D8CFF),
        )

        val Black = Dark.copy(
            name = "black", label = "Black",
            bg1 = Color(0xFF000000), bg2 = Color(0xFF0D1317), altBg1 = Color(0xFF1C262D), altBg2 = Color(0xFF2A3842),
            topbar = Color(0xFF000000),
        )

        val Light = Palette(
            "light", "Light", false,
            bg1 = Color(0xFFF2F5F7), bg2 = Color(0xFFFFFFFF), fg1 = Color(0xFF13212B), fg2 = Color(0xFF3F515D),
            altBg1 = Color(0xFFD3DDE3), altBg2 = Color(0xFFE3EAEE),
            topbar = Color(0xFFF2F5F7), topbarText = Color(0xFF13212B),
            primary = Color(0xFF0E7C6E), accent = Color(0xFFC9761E),
            blue = Color(0xFF1F6FB2), green = Color(0xFF2A9D5C), yellow = Color(0xFFB8920F), orange = Color(0xFFD2691E),
            red = Color(0xFFD1403D), cyan = Color(0xFF12939C), magenta = Color(0xFFB23A82), violet = Color(0xFF6A5ACD),
        )

        val all = listOf(Light, Dark, Black)

        /** Unknown names (including themes from older versions) fall back to following the system. */
        fun resolve(name: String?, systemDark: Boolean): Palette =
            all.firstOrNull { it.name == name } ?: if (systemDark) Dark else Light
    }
}
