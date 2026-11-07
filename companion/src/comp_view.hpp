#pragma once

#include "api_client.hpp"
#include "palette.hpp"
#include "snap.hpp"

#include <imgui.h>
#include <optional>
#include <utility>

namespace midicomp {

/**
 * The comp editor window: toolbar, comp lane and one lane per take.
 *
 * Dragging across a take lane assigns the snapped range to that take; dragging across the comp
 * lane clears it. With the pointer over the lanes, keys 1-9 assign the grid cell under the pointer
 * to the n-th take and 0 clears it.
 */
class CompView {
  public:
    /**
     * Draws the editor into the current ImGui frame and sends commands for user actions.
     *
     * Args:
     *     client: connection to the extension.
     */
    void draw(ApiClient& client);

    /**
     * Sets the colors used for lanes, notes and takes.
     *
     * Args:
     *     palette: colors derived from the Bitwig theme.
     */
    void setPalette(Palette palette) { palette_ = std::move(palette); }

    /** Returns whether an interaction is in progress and the UI should redraw continuously. */
    [[nodiscard]] bool animating() const { return drag_.has_value() || redraw_; }

  private:
    /** A drag in progress on lane ``lane`` (take identifier, or ``-1`` for the comp lane). */
    struct Drag {
        int lane = -1;
        int startStep = 0;
        int endStep = 0;
    };

    /** Screen-space geometry of the lanes for the current frame. */
    struct Layout {
        ImVec2 origin;          ///< Screen position of content coordinate (0, 0).
        float headerWidth = 0;  ///< Width of the pinned name column; lanes start right of it.
        float pxPerStep = 0;
        float visibleLeft = 0;   ///< First visible content x right of the header column.
        float visibleRight = 0;  ///< Last visible content x.
        std::pair<int, int> keys{60, 72};
    };

    void drawToolbar(ApiClient& client, const State* state);
    void drawStart(ApiClient& client, const State& state);
    void drawRecordHint(const State& state);
    void drawHints(const State& state) const;
    /**
     * Draws the three workflow steps as a centered row of numbered circles.
     *
     * Args:
     *     dl: draw list of the current window.
     *     origin: screen position of the area's top left corner.
     *     width: area width to center in.
     *     y: vertical offset inside the area.
     *     active: index of the current step, highlighted.
     *
     * Returns:
     *     The height of the row.
     */
    float drawSteps(ImDrawList* dl, ImVec2 origin, float width, float y, int active) const;
    void drawSessionStatus(const State& state);
    void drawLanes(ApiClient& client, const State& state);
    void drawRuler(ImDrawList* dl, const State& state, const Layout& layout, float y) const;
    void drawGrid(ImDrawList* dl, const State& state, const Layout& layout, float y,
                  float height) const;
    void drawNotes(ImDrawList* dl, const std::vector<Note>& notes, const State& state,
                   const Layout& layout, float y, float height, int laneTake) const;
    void drawSegments(ImDrawList* dl, const State& state, const Layout& layout, float y,
                      float height, int laneTake) const;
    void handleLaneInput(ApiClient& client, const State& state, const Layout& layout, int lane,
                         float y, float height);
    void handleKeys(ApiClient& client, const State& state, const Layout& layout);
    void drawTakeHeader(ApiClient& client, const State& state, const Take& take, float y,
                        float height) const;

    [[nodiscard]] int stepAt(const Layout& layout, float screenX) const;
    [[nodiscard]] int gridSteps(const State& state) const;
    void commitRange(ApiClient& client, const State& state, int lane, int a, int b) const;

    Palette palette_;
    Snap snap_ = Snap::Bar;
    float pxPerBar_ = 160.0f;
    std::optional<Drag> drag_;
    float scrollX_ = 0.0f;                   ///< Horizontal scroll shared by both lane areas.
    std::optional<float> requestedScrollX_;  ///< Scroll position to apply next frame.
    bool redraw_ = false;                    ///< Another frame is needed to settle the layout.
};

}  // namespace midicomp
