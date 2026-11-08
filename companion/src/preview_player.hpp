#pragma once

#include "api_client.hpp"
#include "midi_input.hpp"
#include "state.hpp"

#include <array>
#include <atomic>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

class RtMidiIn;
class RtMidiOut;

namespace midicomp {

/**
 * Follows Bitwig's MIDI clock: 24 pulses per quarter note, start, continue, stop and song
 * position pointers. Gives the beat at any time from the timestamps of recent pulses.
 *
 * Bitwig sends a bare start also when playback starts in the middle of the song, so the beat
 * counts from the start message unless a song position pointer said where the song is
 * (:meth:`positioned`).
 */
class ClockFollower {
  public:
    /** Start (``0xFA``): playback from the beginning. */
    void start(std::int64_t timeMicros);
    /** Continue (``0xFB``): playback from the last song position. */
    void resume(std::int64_t timeMicros);
    /** Stop (``0xFC``). */
    void stop();
    /**
     * Song position pointer (``0xF2``).
     *
     * Args:
     *     sixteenths: position in sixteenth notes since the start of the song.
     */
    void songPosition(int sixteenths);
    /** Timing clock (``0xF8``). */
    void pulse(std::int64_t timeMicros);

    /** Returns whether a song position pointer gave the beat since the last start or stop. */
    [[nodiscard]] bool positioned() const { return positioned_; }

    /** Counts start, continue, stop and song position messages, to notice a new playback. */
    [[nodiscard]] int epoch() const { return epoch_; }

    /** Returns whether the clock runs and has sent a pulse recently. */
    [[nodiscard]] bool running(std::int64_t nowMicros) const;

    /**
     * Returns the beat (quarter notes since the song start) at a time.
     *
     * Args:
     *     timeMicros: monotonic time.
     *
     * Returns:
     *     The beat, or ``std::nullopt`` before a few pulses have arrived.
     */
    [[nodiscard]] std::optional<double> beatAt(std::int64_t timeMicros) const;

  private:
    bool running_ = false;
    bool positioned_ = false;
    int epoch_ = 0;
    double startBeat_ = 0.0;  ///< Beat of the first pulse after start or continue.
    long long pulses_ = 0;    ///< Pulses since then.
    std::int64_t lastPulse_ = 0;
    std::vector<std::pair<std::int64_t, double>> recent_;  ///< ``(time, beat)`` of recent pulses.
};

/** A MIDI message of the comp at a position in steps. */
struct PreviewEvent {
    double step = 0.0;
    int order = 0;  ///< At the same step: note-offs (0), then controllers (1), then note-ons (2).
    std::array<std::uint8_t, 3> bytes{};
    std::uint8_t size = 3;
};

/** The comp as a timeline of MIDI messages, for playing it. */
class PreviewEvents {
  public:
    /**
     * Builds the timeline from a state: comp notes (on and off) and comp controllers.
     *
     * Args:
     *     state: the extension state.
     */
    void build(const State& state);

    /** Returns the messages with ``from < step <= to``, in order. */
    [[nodiscard]] std::vector<PreviewEvent> between(double from, double to) const;

    /**
     * Returns the latest message of each controller of the comp at or before ``step``, or its
     * resting value (0, pitch bend centered) where it has none yet.
     */
    [[nodiscard]] std::vector<PreviewEvent> controllersAt(double step) const;

    [[nodiscard]] std::size_t size() const { return events_.size(); }

  private:
    std::vector<PreviewEvent> events_;
};

/**
 * Plays the comp as live MIDI through a virtual port while the transport plays, so it can be
 * heard in Bitwig without writing the clip. Timing comes from Bitwig's MIDI clock when it
 * arrives (Linux: the port's VirMIDI copy), else from the extension's transport fit.
 */
class PreviewPlayer {
  public:
    /**
     * Args:
     *     playhead: supplies the latest playhead (thread-safe), for the comp's position in the
     *         arrangement, the loop and the transport fit.
     */
    explicit PreviewPlayer(std::function<Playhead()> playhead);
    ~PreviewPlayer();

    PreviewPlayer(const PreviewPlayer&) = delete;
    PreviewPlayer& operator=(const PreviewPlayer&) = delete;

    /** Returns whether the virtual output port could be created. */
    [[nodiscard]] bool available() const { return out_ != nullptr; }

    /** Returns an error, for example on platforms without virtual ports. */
    [[nodiscard]] std::string error() const;

    /** Turns playing on or off; off sends note-offs for sounding notes. */
    void setEnabled(bool on) { enabled_ = on; }

    /** Sends messages this much early, to make up for Bitwig's output latency. */
    void setLeadMillis(double ms) { leadMicros_ = static_cast<std::int64_t>(ms * 1000.0); }

    /** Replaces the comp being played. */
    void setComp(const State& state);

    /** Returns how timing is derived right now: "MIDI clock", "transport" or "". */
    [[nodiscard]] std::string timingSource() const;

    /** Returns the number of MIDI messages sent so far. */
    [[nodiscard]] long long sent() const { return sent_.load(); }

  private:
    void run();
    void openClockInput();
    void onClock(double delta, const std::vector<unsigned char>& message);
    [[nodiscard]] std::optional<double> stepAt(std::int64_t timeMicros, const Playhead& p);
    [[nodiscard]] std::optional<double> clockStep(std::int64_t timeMicros, double beat,
                                                  std::optional<double> transport,
                                                  const Playhead& p);
    void send(const PreviewEvent& e);
    void allNotesOff();
    void sendControllers(const PreviewEvents& events, double step);

    std::function<Playhead()> playhead_;
    std::unique_ptr<RtMidiOut> out_;
    std::unique_ptr<RtMidiIn> clockIn_;
    std::string error_;

    mutable std::mutex mutex_;  ///< Guards the comp, the clock and the timing source.
    std::shared_ptr<const PreviewEvents> events_ = std::make_shared<PreviewEvents>();
    bool compChanged_ = false;
    ClockFollower clock_;
    TimestampAnchor clockAnchor_;
    std::string timing_;

    // Player thread:
    std::map<int, int> sounding_;              ///< ``channel << 8 | key`` to number of note-ons.
    std::map<int, PreviewEvent> controllers_;  ///< Last message sent per controller.
    double lastStep_ = -1.0;
    int clockEpoch_ = -1;
    std::optional<double> clockOffset_;          ///< Comp step at clock beat 0.
    std::optional<std::int64_t> mismatchSince_;  ///< Since when clock and transport disagree.
    std::atomic<bool> enabled_{false};
    std::atomic<std::int64_t> leadMicros_{10'000};
    std::atomic<bool> stopping_{false};
    std::atomic<long long> sent_{0};
    std::thread thread_;
};

}  // namespace midicomp
