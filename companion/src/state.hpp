#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace midicomp {

/** A note in grid coordinates as sent by the extension. */
struct Note {
    int x = 0;              ///< Start step.
    int key = 0;            ///< MIDI key, 0-127.
    double duration = 0.0;  ///< Length in steps.
    double velocity = 0.0;  ///< 0-1.
    bool muted = false;
    int take = -1;  ///< Source take for comp notes, ``-1`` for take notes.
};

/** A captured take. */
struct Take {
    int id = 0;
    std::string name;
    std::string source;  ///< ``"launcher"`` or ``"arranger"``.
    int length = 0;      ///< Length in steps.
    std::vector<Note> notes;
};

/** A range ``[from, to)`` in steps that plays the notes of take ``take``. */
struct Segment {
    int from = 0;
    int to = 0;
    int take = 0;
};

/** The comp session: the clip being comped and its sync state. */
struct Session {
    std::string source;  ///< ``"launcher"`` or ``"arranger"``.
    std::string label;
    bool available = false;   ///< The clip is under its cursor (arranger clips must be selected).
    bool recording = false;   ///< Bitwig is recording into the clip.
    bool pending = false;     ///< Comp changes wait to be written.
    bool truncated = false;   ///< The comp is longer than the clip.
    int clipLength = -1;      ///< Clip length in steps, ``-1`` while it is not under its cursor.
    int passes = 0;           ///< Loop passes recorded in this session.
    bool singlePass = false;  ///< Recording stops at the end of the current pass.
    int recordingPass = 0;    ///< Loop pass being recorded (1-based), 0 when not recording.
};

/** Snapshot of the extension state, see ``API.md``. */
struct State {
    long long version = -1;
    int stepsPerBeat = 128;
    int barSteps = 512;
    int length = 0;
    std::string project;  ///< Name of the open Bitwig project.
    std::optional<std::string> launcherSel;
    std::optional<std::string> arrangerSel;
    bool canUndoEdit = false;
    bool canRevert = false;
    std::string message;
    std::optional<Session> session;
    std::vector<std::string> log;  ///< Recent extension events, oldest first.
    std::vector<Take> takes;
    std::vector<Segment> segments;
    std::vector<Note> comp;

    /** Returns the take with identifier ``id``, or ``nullptr``. */
    [[nodiscard]] const Take* findTake(int id) const;

    /** Returns the index of take ``id`` in :member:`takes`, or ``-1``. */
    [[nodiscard]] int takeIndex(int id) const;

    /**
     * Returns the lowest and highest key over all takes as ``(low, high)``.
     *
     * The range spans at least one octave so that sparse takes stay readable.
     */
    [[nodiscard]] std::pair<int, int> keyRange() const;
};

/**
 * Parses a state document.
 *
 * Args:
 *     json: body of ``GET /api/v1/state``.
 *
 * Returns:
 *     The parsed state.
 *
 * Raises:
 *     nlohmann::json::exception: if the document is malformed.
 */
State parseState(std::string_view json);

/** Result of a command, parsed from ``{"ok": ..., "message": ...}``. */
struct Reply {
    bool ok = false;
    std::string message;
};

/**
 * Parses a command reply; malformed bodies produce ``ok == false`` with the raw text.
 *
 * Args:
 *     json: response body.
 */
Reply parseReply(std::string_view json);

}  // namespace midicomp
