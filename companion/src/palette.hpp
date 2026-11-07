#pragma once

#include "theme.hpp"

#include <imgui.h>
#include <vector>

namespace midicomp {

/** Colors used by the comp editor, derived from a Bitwig theme. */
struct Palette {
    ImU32 windowBg = 0;
    ImU32 compBg = 0;    ///< Comp lane: Bitwig's light timeline background.
    ImU32 laneBg = 0;    ///< Take lanes: Bitwig's dark timeline background.
    ImU32 headerBg = 0;  ///< Name column and ruler: timeline header background.
    ImU32 laneSeparator = 0;
    ImU32 barLine = 0;
    ImU32 beatLine = 0;
    ImU32 text = 0;
    ImU32 mutedText = 0;
    ImU32 inactiveNote = 0;
    ImU32 selectionFill = 0;  ///< Drag preview, as Bitwig's time selection.
    ImU32 selectionStroke = 0;
    ImU32 clearFill = 0;  ///< Drag preview on the comp lane (clearing).
    ImU32 clearStroke = 0;
    ImU32 accent = 0;
    ImU32 live = 0;
    ImU32 warning = 0;
    ImU32 error = 0;
    std::vector<ImU32> takes;  ///< Take colors from Bitwig's panel color set.

    /**
     * Returns the color of a take.
     *
     * Args:
     *     takeId: take identifier.
     *     alpha: opacity, 0-1.
     */
    [[nodiscard]] ImU32 take(int takeId, float alpha = 1.0f) const;
};

/**
 * Builds the editor palette from a theme, falling back to Bitwig's stock dark colors.
 *
 * Args:
 *     theme: merged Bitwig Theme Editor colors; may be empty.
 */
Palette makePalette(const Theme& theme);

/**
 * Applies theme colors and Bitwig-like metrics to the current ImGui style.
 *
 * Args:
 *     theme: merged Bitwig Theme Editor colors; may be empty.
 *     scale: content scale of the window (1 for standard DPI).
 */
void applyStyle(const Theme& theme, float scale);

/** Returns ``color`` with opacity ``alpha`` (0-1). */
ImU32 withAlpha(ImU32 color, float alpha);

/** Returns ``color`` darkened towards black by ``amount`` (0-1), keeping its alpha. */
ImU32 darken(ImU32 color, float amount);

}  // namespace midicomp
