#pragma once

#include "api_client.hpp"
#include "comp_store.hpp"
#include "midi_input.hpp"
#include "palette.hpp"
#include "preview_player.hpp"
#include "settings.hpp"
#include "snap.hpp"

#include <array>
#include <atomic>
#include <filesystem>
#include <imgui.h>
#include <map>
#include <optional>
#include <string>
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

    /**
     * Enables saving comps to ``store`` and offering them for restore.
     *
     * Args:
     *     store: the local comp store; must outlive the view.
     */
    void setStore(CompStore* store) { store_ = store; }

    /**
     * Enables the keyboard input: a port picker in the toolbar, and forwarding of the played
     * notes to the extension during comp sessions.
     *
     * Args:
     *     midi: the keyboard input; must outlive the view.
     */
    void setMidi(MidiInput* midi) { midi_ = midi; }

    /**
     * Shows a keyboard name in the picker without MIDI input, for screenshots.
     *
     * Args:
     *     name: the name to show.
     */
    void setDemoKeyboard(std::string name) { demoKeyboard_ = std::move(name); }

    /**
     * Enables previews: the player plays the comp as live MIDI while the extension reports a
     * preview.
     *
     * Args:
     *     player: the preview player; must outlive the view, or be reset to null first.
     */
    void setPreviewPlayer(PreviewPlayer* player) { previewPlayer_ = player; }

    /**
     * Sets the user's UI scale factor shown in the View popup.
     *
     * Args:
     *     scale: factor on top of the display's scale.
     */
    void setUiScale(const float scale) { uiScale_ = scale; }

    /**
     * Uses ``settings`` for the lane height and saves changes to ``path``.
     *
     * Args:
     *     settings: preferences; must outlive the view.
     *     path: the settings file.
     */
    void setSettings(Settings* settings, std::filesystem::path path) {
        settings_ = settings;
        settingsPath_ = std::move(path);
    }

    /** Opens the saved-comps list (for screenshots). */
    void openSavedComps() { openSavedComps_ = true; }

    /** Returns a UI scale factor the user chose since the last call, if any. */
    [[nodiscard]] std::optional<float> takeUiScaleRequest() {
        return std::exchange(uiScaleRequest_, std::nullopt);
    }

    /** Returns whether an interaction is in progress and the UI should redraw continuously. */
    [[nodiscard]] bool animating() const {
        return drag_.has_value() || redraw_ || playhead_.playing;
    }

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
    /** Draws the play/stop button. */
    void drawTransportButton(ApiClient& client);
    /** Space: play or stop; Left, Right, Home: move the playhead. */
    void handleTransportKeys(ApiClient& client, const State& state);
    /** Moves the playhead to a clicked ruler position (arranger clips). */
    void handleRulerInput(ApiClient& client, const State& state, const Layout& layout,
                          float height);
    /** Draws the playhead over ``[y, y + height)``; ``marker`` adds the triangle on the ruler. */
    void drawPlayhead(ImDrawList* dl, const State& state, const Layout& layout, float y,
                      float height, bool marker) const;
    void drawGrid(ImDrawList* dl, const State& state, const Layout& layout, float y,
                  float height) const;
    void drawNotes(ImDrawList* dl, const std::vector<Note>& notes, const State& state,
                   const Layout& layout, float y, float height, int laneTake) const;
    /**
     * Draws control messages as step curves in the lower part of a lane: sustain (CC 64) in
     * green, pitch bend in the accent color, other controllers muted.
     */
    void drawControls(ImDrawList* dl, const std::vector<ControlPoint>& controls, const State& state,
                      const Layout& layout, float y, float height) const;
    void drawSegments(ImDrawList* dl, const State& state, const Layout& layout, float y,
                      float height, int laneTake) const;
    void handleLaneInput(ApiClient& client, const State& state, const Layout& layout, int lane,
                         float y, float height);
    void handleKeys(ApiClient& client, const State& state, const Layout& layout);
    /** Draws a take's header; double-clicking the name renames the take. */
    void drawTakeHeader(ApiClient& client, const State& state, const Take& take, float y,
                        float height);
    void drawPreviewHeader(const State& state, const PreviewPass& pass, float y,
                           float height) const;
    /**
     * Grays out the area below the toolbar and explains that a clip must be selected in
     * Bitwig.
     *
     * Args:
     *     state: current state, with a session.
     *     origin: screen position of the area's top left corner.
     *     size: size of the area.
     */
    void drawSelectOverlay(const State& state, ImVec2 origin, ImVec2 size) const;

    [[nodiscard]] int stepAt(const Layout& layout, float screenX) const;
    [[nodiscard]] int gridSteps(const State& state) const;
    void commitRange(ApiClient& client, const State& state, int lane, int a, int b) const;

    void drawMidiPicker();
    void drawViewMenu();
    void handleScaleKeys();
    void forwardMidi(ApiClient& client, const State* state);
    void drawLog(const State& state);
    void updateStore(ApiClient& client, const State* state);
    void drawRestoreOffer(ApiClient& client);
    /** Draws the popup listing the project's saved comps, the best guess selected. */
    void drawSavedComps(ApiClient& client, const State& state);
    /** Fills the saved-comps list for the session, the best guess selected. */
    void refreshSavedChoices(const State& state);
    /** Reloads the list after renaming or deleting, keeping the selection. */
    void refreshAfterChange(const State& state);
    /** Fills the name fields of the list from the selection. */
    void syncNameFields();
    /** Returns the name of a recording set, or when it was recorded. */
    [[nodiscard]] std::string recordingTitle(const RecordingSet& set) const;
    /** Imports a saved comp into the session and adopts its name. */
    void restore(ApiClient& client, const SavedComp& comp);
    /** Saves the comp now (for example after renaming), in the background. */
    void saveNow(ApiClient& client);
    [[nodiscard]] const SavedComp* savedFor(const State& state, const std::string& clip) const;

    Palette palette_;
    bool stopAtLoopEnd_ = true;
    bool showLog_ = false;
    double pendingSince_ = -1.0;  ///< When the current pending write was first seen.

    // Keyboard input.
    MidiInput* midi_ = nullptr;
    std::string demoKeyboard_;
    PreviewPlayer* previewPlayer_ = nullptr;
    long long previewVersion_ = -1;  ///< State version the player's comp comes from.
    Settings* settings_ = nullptr;
    std::filesystem::path settingsPath_;
    std::string compName_;                     ///< Name of the current session's comp.
    std::array<char, 128> nameBuffer_{};       ///< Text field contents for :member:`compName_`.
    std::vector<RecordingSet> recordingSets_;  ///< The list shown by :meth:`drawSavedComps`.
    int savedSet_ = 0;                         ///< Selected recording set.
    int savedChoice_ = 0;                      ///< Selected comp in that set.
    bool showAllClips_ = false;
    std::map<std::string, std::string> recordingNames_;  ///< takesKey to name.
    std::array<char, 128> recordingNameBuffer_{};
    std::array<char, 128> savedNameBuffer_{};
    bool deleteArmed_ = false;     ///< "Delete" was clicked once.
    bool openSavedComps_ = false;  ///< Open the saved-comps popup on the next frame.
    float uiScale_ = 1.0f;
    std::optional<float> uiScaleRequest_;
    float scaleDraft_ = 100.0f;  ///< Slider value in percent while it is being dragged.
    bool scaleSliderActive_ = false;
    std::vector<std::string> midiPorts_;  ///< Listed when the picker opens.
    std::int64_t lastMidiPost_ = 0;

    // Local comp store.
    CompStore* store_ = nullptr;
    std::vector<SavedComp> saved_;  ///< Cached list, newest first.
    double savedListAt_ = -100.0;
    std::atomic<bool> storeChanged_{false};
    std::string sessionKey_;  ///< Project and clip of the current session.
    std::string sessionId_;   ///< File identifier of the current session.
    std::optional<SavedComp> restoreOffer_;
    std::string restoreClip_;  ///< Label of the session the offer was made for.
    /** "New version" was pressed: the copy gets the comp from the extension, not an offer. */
    std::atomic<bool> versionRequested_{false};
    std::atomic<bool> versionSaved_{false};  ///< The comp was saved; send ``newVersion``.
    long long lastVersion_ = -1;
    double lastChange_ = 0.0;
    std::atomic<long long> savedVersion_{-1};
    std::atomic<bool> saving_{false};
    Snap snap_ = Snap::Bar;
    float pxPerBar_ = 160.0f;
    std::optional<Drag> drag_;
    float scrollX_ = 0.0f;                   ///< Horizontal scroll shared by both lane areas.
    std::optional<float> requestedScrollX_;  ///< Scroll position to apply next frame.
    bool redraw_ = false;                    ///< Another frame is needed to settle the layout.
    bool inputBlocked_ = false;
    Playhead playhead_;                      ///< Snapshot for the current frame.
    PlayheadFollower follower_;              ///< Smooths :member:`playhead_` between updates.
    double playheadStep_ = -1.0;             ///< Position to draw this frame.
    int renamingTake_ = -1;                  ///< Take whose name is being edited, or -1.
    bool renameFocus_ = false;               ///< Focus the name field on the next frame.
    std::array<char, 64> takeNameBuffer_{};  ///< A clip must be selected in Bitwig before editing.
    int laneCount_ = -1;                     ///< Lanes shown last frame, ``-1`` outside a session.
    bool scrollToBottom_ = false;            ///< Scroll the take area to the newest lane.
};

}  // namespace midicomp
