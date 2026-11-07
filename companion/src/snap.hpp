#pragma once

#include <algorithm>
#include <array>
#include <optional>
#include <string_view>
#include <utility>

namespace midicomp {

/** Grid used to widen dragged ranges. */
enum class Snap { Bar, Beat, Eighth, Sixteenth, Off };

inline constexpr std::array<std::pair<Snap, std::string_view>, 5> kSnapNames{{
    {Snap::Bar, "Bar"},
    {Snap::Beat, "Beat"},
    {Snap::Eighth, "1/8"},
    {Snap::Sixteenth, "1/16"},
    {Snap::Off, "Off"},
}};

/**
 * Returns the snap grid size in steps.
 *
 * Args:
 *     snap: grid choice.
 *     stepsPerBeat: steps per quarter note.
 *     barSteps: steps per bar.
 */
[[nodiscard]] constexpr int snapSteps(const Snap snap, const int stepsPerBeat, const int barSteps) {
    switch (snap) {
    case Snap::Bar:
        return std::max(1, barSteps);
    case Snap::Beat:
        return std::max(1, stepsPerBeat);
    case Snap::Eighth:
        return std::max(1, stepsPerBeat / 2);
    case Snap::Sixteenth:
        return std::max(1, stepsPerBeat / 4);
    case Snap::Off:
        return 1;
    }
    return 1;
}

/**
 * Widens a dragged range to the snap grid and clamps it to the comp.
 *
 * A click (``a == b``) selects the grid cell under the pointer; with ``Snap::Off`` a click
 * selects nothing.
 *
 * Args:
 *     a: step where the drag started.
 *     b: step where the drag ended.
 *     grid: snap size in steps, at least 1.
 *     length: comp length in steps.
 *
 * Returns:
 *     ``(from, to)`` with ``to`` exclusive, or ``std::nullopt`` for an empty range.
 */
[[nodiscard]] constexpr std::optional<std::pair<int, int>>
snapRange(const int a, const int b, const int grid, const int length) {
    const int lo = std::min(a, b);
    const int hi = std::max(a, b);
    if (grid <= 1 && lo == hi)
        return std::nullopt;

    // Floor/ceil division that also works for negative positions left of the clip start.
    const auto floorDiv = [](const int n, const int d) {
        return n / d - ((n % d != 0) && (n < 0));
    };
    int from = floorDiv(lo, grid) * grid;
    int to = (floorDiv(hi - 1, grid) + 1) * grid;
    if (to <= from)
        to = from + grid;

    from = std::clamp(from, 0, length);
    to = std::clamp(to, 0, length);
    if (to <= from)
        return std::nullopt;
    return std::pair{from, to};
}

}  // namespace midicomp
