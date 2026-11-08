#pragma once

#include <atomic>
#include <cstdint>
#include <filesystem>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

class RtMidiIn;

namespace midicomp {

/** A message from the keyboard: a note, or a control message (CC, pressure, pitch bend). */
struct MidiEvent {
    std::int64_t timeMicros = 0;  ///< On the monotonic clock, see :func:`monotonicMicros`.
    std::uint8_t status = 0;
    std::uint8_t data1 = 0;
    std::uint8_t data2 = 0;

    bool operator==(const MidiEvent&) const = default;
};

/**
 * Returns the monotonic clock in microseconds. It is the clock behind Java's
 * ``System.nanoTime()`` on the same machine, so the extension can place the events directly.
 */
std::int64_t monotonicMicros();

/**
 * Converts MIDI driver timestamps to the monotonic clock.
 *
 * Drivers report the time between messages, measured when the message arrived at the driver; the
 * moment a message reaches the application is later by a varying amount. The anchor (monotonic
 * time of the driver's time zero) is therefore the earliest arrival minus elapsed driver time
 * seen so far: the message delivered with the least delay sets it.
 */
class TimestampAnchor {
  public:
    /**
     * Adds a message.
     *
     * Args:
     *     deltaSeconds: driver time since the previous message.
     *     arrivalMicros: monotonic time at which the message was delivered.
     *
     * Returns:
     *     The driver time of the message since the first one, in seconds.
     */
    double add(double deltaSeconds, std::int64_t arrivalMicros);

    /**
     * Returns the monotonic time of a message.
     *
     * Args:
     *     elapsedSeconds: value returned by :meth:`add` for it.
     */
    [[nodiscard]] std::int64_t place(double elapsedSeconds) const;

    /** Forgets everything, for a newly opened port. */
    void reset();

  private:
    double elapsed_ = 0.0;
    std::optional<std::int64_t> anchor_;
};

/**
 * Returns the body of ``POST /api/v1/played``:
 * ``{"now": <µs>, "events": [[<µs>, status, data1, data2], ...]}``. ``now`` lets the extension
 * check that both sides use the same clock.
 *
 * Args:
 *     events: messages to send.
 *     nowMicros: monotonic time of sending.
 */
std::string playedJson(std::span<const MidiEvent> events, std::int64_t nowMicros);

/**
 * Returns a port name without the client and port numbers some drivers append (ALSA:
 * ``"USB Axiom 61:USB Axiom 61 MIDI 1 20:0"``), which change when devices are reconnected.
 */
std::string portKey(std::string_view name);

/**
 * Chooses the port to open.
 *
 * Args:
 *     ports: available port names.
 *     preferred: key (see :func:`portKey`) of the port chosen earlier, or empty.
 *
 * Returns:
 *     The index of the preferred port if present; without a preference the first port that is
 *     not a loopback or virtual port; otherwise ``std::nullopt``.
 */
std::optional<std::size_t> choosePort(const std::vector<std::string>& ports,
                                      std::string_view preferred);

/**
 * Keyboard input: one MIDI input port, whose note messages are collected with timestamps.
 *
 * The port chosen by the user is remembered in a small file and reopened automatically,
 * also when the keyboard is connected later.
 */
class MidiInput {
  public:
    /**
     * Args:
     *     settingsFile: file that remembers the chosen port.
     *     onNote: called from the driver's thread for each note message; typically wakes the
     *         UI loop.
     */
    MidiInput(std::filesystem::path settingsFile, std::function<void()> onNote);
    ~MidiInput();

    MidiInput(const MidiInput&) = delete;
    MidiInput& operator=(const MidiInput&) = delete;

    /** Returns whether MIDI input works on this system. */
    [[nodiscard]] bool available() const { return in_ != nullptr; }

    /** Returns the names of the input ports. */
    [[nodiscard]] std::vector<std::string> ports();

    /**
     * Opens a port and remembers the choice.
     *
     * Args:
     *     name: port name from :meth:`ports`.
     *
     * Returns:
     *     Whether the port was opened.
     */
    bool open(const std::string& name);

    /** Closes the port and remembers that no keyboard is wanted. */
    void close();

    /**
     * Reopens the remembered port, or the first keyboard if none was chosen yet, unless the
     * user closed the port; does nothing while a port is open. Rate-limited, so it can be
     * called every frame.
     */
    void reconnect();

    /** Returns the name of the open port, or an empty string. */
    [[nodiscard]] std::string portName() const;

    /** Returns the last error, or an empty string. */
    [[nodiscard]] std::string error() const;

    /** Returns and removes the collected messages. */
    std::vector<MidiEvent> take();

    /** Returns the monotonic time of the latest note message, or 0. */
    [[nodiscard]] std::int64_t lastNoteMicros() const { return lastNote_.load(); }

  private:
    struct Pending {
        double elapsed = 0.0;
        std::uint8_t status = 0;
        std::uint8_t data1 = 0;
        std::uint8_t data2 = 0;
    };

    static void onMessage(double delta, std::vector<unsigned char>* message, void* self);
    void receive(double delta, const std::vector<unsigned char>& message);
    void savePreference(std::string_view key) const;
    [[nodiscard]] std::optional<std::string> loadPreference() const;

    const std::filesystem::path settingsFile_;
    const std::function<void()> onNote_;
    std::unique_ptr<RtMidiIn> in_;

    mutable std::mutex mutex_;  ///< Guards the members below; the driver thread writes them.
    TimestampAnchor anchor_;
    std::vector<Pending> pending_;
    std::string portName_;
    std::string error_;
    std::int64_t lastReconnect_ = 0;
    std::atomic<std::int64_t> lastNote_{0};
};

}  // namespace midicomp
