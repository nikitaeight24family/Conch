package ai.eight24family.conch.ui.theme

import androidx.compose.ui.graphics.Color

// Cyberpunk-80s × 2026 — high-contrast neon over near-black, intentionally
// designed for a CLI / terminal feel.

// Surfaces
val Bg              = Color(0xFF06090F)   // near-black background
val Surface         = Color(0xFF0C1119)
val SurfaceVariant  = Color(0xFF131B26)
val SurfaceHigh     = Color(0xFF1A2434)
val Outline         = Color(0xFF2D3D55)
val OutlineDim      = Color(0xFF1E2A3C)

// Foreground
val Fg              = Color(0xFFD9E3F1)
val FgDim           = Color(0xFF8FA0B8)
val FgFaint         = Color(0xFF566378)

// Neon accents
val Cyan            = Color(0xFF00E5FF)
val CyanDim         = Color(0xFF18A2B8)
val Magenta         = Color(0xFFFF2BD6)
val Lime            = Color(0xFFA8FF60)
val Amber           = Color(0xFFFFC857)
val HotPink         = Color(0xFFFF4365)

// Semantic role mapping
val RolePrimary           = Cyan
val RoleOnPrimary         = Bg
val RolePrimaryContainer  = Color(0xFF0E2A33)
val RoleOnPrimaryContainer= Cyan

val RoleSecondary         = Magenta
val RoleOnSecondary       = Bg
val RoleSecondaryContainer= Color(0xFF2C0E26)
val RoleOnSecondaryContainer = Magenta

val RoleTertiary          = Amber
val RoleOnTertiary        = Bg
val RoleTertiaryContainer = Color(0xFF2A210A)
val RoleOnTertiaryContainer = Amber

val RoleError             = HotPink
val RoleOnError           = Bg
val RoleErrorContainer    = Color(0xFF2A0E18)
val RoleOnErrorContainer  = HotPink

// ─────────────── Light scheme ───────────────
// "Cyberpunk on paper" — same shapes, inverted palette. Backgrounds get
// a warm near-white (not pure #FFFFFF — too clinical), foreground a deep
// graphite. Neon accents stay neon-ish but get darkened-for-legibility
// before being used as `primary` text/icon tint on light surfaces.
val LightBg             = Color(0xFFF5F1E8)
val LightSurface        = Color(0xFFEDE7DA)
val LightSurfaceVariant = Color(0xFFE3DCC8)
val LightOutline        = Color(0xFFA89F8C)
val LightOutlineDim     = Color(0xFFC8C2B0)
val LightFg             = Color(0xFF1A1F26)
val LightFgDim          = Color(0xFF4A5260)
