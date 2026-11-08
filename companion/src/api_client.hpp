#pragma once

#include "state.hpp"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <filesystem>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace httplib {
class Client;
}

namespace midicomp {

/** Location and credentials of the extension's API, read from the discovery file. */
struct Endpoint {
    std::string url;  ///< For example ``http://127.0.0.1:8765``.
    std::string token;

    bool operator==(const Endpoint&) const = default;
};

/** The playhead in the session clip, from ``GET /api/v1/playhead``. */
struct Playhead {
    bool playing = false;
    int step = -1;  ///< Step being played, or -1 outside the clip.
    double stepsPerSecond = 0.0;
    bool canSeek = false;           ///< ``seek`` works (arranger clip whose position is known).
    bool transportPlaying = false;  ///< Bitwig plays, inside the clip or not.
    bool auditioning = false;       ///< The companion plays the comp (preview).
    /** Arrangement beat of the comp's step 0 (arranger comps whose position is known). */
    std::optional<double> originBeat;
    double stepSize = 0.0;            ///< Beats per step, with :member:`originBeat`.
    std::optional<double> playStart;  ///< Bitwig's play start position in steps.
    std::optional<bool> noteInput;    ///< Whether the comp track has a note input.
    std::optional<int> previewHeard;  ///< While previewing: notes the comp track played.
    std::optional<std::pair<double, double>> loop;  ///< Arranger loop region in steps.
    /** Fitted transport while playing: the step at a time (monotonic µs). */
    std::optional<std::pair<std::int64_t, double>> anchor;
    std::chrono::steady_clock::time_point at{};  ///< When it was received.

    /**
     * Returns the step at ``now``, interpolated while playing.
     *
     * Args:
     *     now: the current time.
     */
    [[nodiscard]] double stepAt(std::chrono::steady_clock::time_point now) const;
};

/**
 * Turns playhead updates into a smoothly moving position.
 *
 * Updates arrive about 20 times a second and lag slightly behind the extrapolated position, so
 * resetting to each one would pull the line back a little every time. Small differences are
 * corrected gradually and never backwards while playing; jumps (loop wraps, seeking, stopping)
 * are taken over at once.
 */
class PlayheadFollower {
  public:
    /**
     * Returns the step to show at ``now``.
     *
     * Args:
     *     latest: the latest playhead update.
     *     now: the current time.
     *
     * Returns:
     *     The step, or a negative value when there is no playhead.
     */
    double position(const Playhead& latest, std::chrono::steady_clock::time_point now);

  private:
    std::chrono::steady_clock::time_point seen_{};  ///< ``at`` of the last update taken over.
    std::chrono::steady_clock::time_point anchorTime_{};
    double anchor_ = -1.0;
    double shown_ = -1.0;
    bool playing_ = false;
    double stepsPerSecond_ = 0.0;
};

/**
 * Parses a playhead document.
 *
 * Args:
 *     json: body of ``GET /api/v1/playhead``.
 *
 * Returns:
 *     The playhead (without :member:`Playhead::at`), or ``std::nullopt`` if malformed.
 */
std::optional<Playhead> parsePlayhead(std::string_view json);

/** Returns ``~/.midicomp/endpoint.json``. */
std::filesystem::path defaultEndpointPath();

/**
 * Reads the discovery file.
 *
 * Args:
 *     path: file written by the extension.
 *
 * Returns:
 *     The endpoint, or ``std::nullopt`` if the file is missing or malformed.
 */
std::optional<Endpoint> readEndpoint(const std::filesystem::path& path);

/**
 * Percent-encodes a query parameter value.
 *
 * Args:
 *     value: raw value.
 */
std::string encodeQueryValue(std::string_view value);

/**
 * Background connection to the extension.
 *
 * A poller thread long-polls ``/api/v1/state`` and publishes each new :class:`State`. A worker
 * thread sends queued commands in order. Both re-read the discovery file after connection errors,
 * so the client follows extension restarts.
 */
class ApiClient {
  public:
    enum class Connection { Searching, Connected, Error };

    /** A command parameter, ``(name, value)``. */
    using Param = std::pair<std::string, std::string>;

    /**
     * Starts the background threads.
     *
     * Args:
     *     endpointPath: discovery file to read.
     *     onChange: called from a background thread whenever state, connection or the last
     *         reply changed; typically wakes the UI loop.
     */
    ApiClient(std::filesystem::path endpointPath, std::function<void()> onChange);
    ~ApiClient();

    ApiClient(const ApiClient&) = delete;
    ApiClient& operator=(const ApiClient&) = delete;

    /** Returns the latest state, or ``nullptr`` before the first successful poll. */
    [[nodiscard]] std::shared_ptr<const State> state() const;

    [[nodiscard]] Connection connection() const;

    /** Returns a human-readable description of the connection status. */
    [[nodiscard]] std::string connectionDetail() const;

    /** Returns the reply to the most recent command, if any. */
    [[nodiscard]] std::optional<Reply> lastReply() const;

    /** Returns the latest playhead. */
    [[nodiscard]] Playhead playhead() const;

    /**
     * Turns polling of the playhead on or off (about 20 times a second while on).
     *
     * Args:
     *     on: whether to poll, typically while a comp session runs.
     */
    void setPollPlayhead(bool on) { pollPlayhead_ = on; }

    /** Returns whether commands are queued or in flight. */
    [[nodiscard]] bool busy() const;

    /**
     * Queues ``POST /api/v1/<op>?<params>``; the reply becomes :meth:`lastReply`.
     *
     * Args:
     *     op: command name.
     *     params: query parameters.
     *     body: request body, for example a comp for ``import``.
     */
    void send(std::string op, std::vector<Param> params = {}, std::string body = {});

    /**
     * Queues ``POST /api/v1/<op>`` without reporting it: the reply is ignored and neither
     * :meth:`busy` nor :meth:`lastReply` change. For data the UI does not wait on.
     *
     * Args:
     *     op: command name.
     *     body: request body.
     */
    void post(std::string op, std::string body);

    /**
     * Queues ``GET /api/v1/<op>`` for a command that returns a document, such as ``export``.
     *
     * Args:
     *     op: command name.
     *     done: called on a background thread with the document, or ``std::nullopt`` if the
     *         request failed or the extension declined it.
     */
    void fetch(std::string op, std::function<void(std::optional<std::string>)> done);

  private:
    struct Command {
        std::string op;
        std::vector<Param> params;
        std::string body;
        /** Set for :meth:`fetch`: the request is a GET and the body goes here. */
        std::function<void(std::optional<std::string>)> onDocument;
        bool silent = false;  ///< Set for :meth:`post`.
    };

    void pollLoop();
    void commandLoop();
    void playheadLoop();
    /** Waits for ``duration`` or until shutdown; returns whether the client is stopping. */
    bool sleepFor(std::chrono::milliseconds duration);
    std::optional<Endpoint> currentEndpoint();
    void setConnection(Connection connection, std::string detail);
    void notify() const;

    const std::filesystem::path endpointPath_;
    const std::function<void()> onChange_;

    mutable std::mutex mutex_;
    std::shared_ptr<const State> state_;
    Connection connection_ = Connection::Searching;
    std::string connectionDetail_ = "Looking for the extension";
    std::optional<Reply> lastReply_;
    std::optional<Endpoint> endpoint_;
    std::deque<Command> commands_;
    bool commandInFlight_ = false;
    std::condition_variable wake_;  ///< Signals new commands and shutdown.
    std::atomic<bool> stopping_{false};
    std::atomic<bool> pollPlayhead_{false};
    Playhead playhead_;                      ///< Guarded by ``mutex_``.
    httplib::Client* pollClient_ = nullptr;  ///< Guarded by ``mutex_``; stopped on shutdown.

    std::thread poller_;
    std::thread worker_;
    std::thread playheadPoller_;
};

}  // namespace midicomp
