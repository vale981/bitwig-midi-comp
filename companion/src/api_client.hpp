#pragma once

#include "state.hpp"

#include <atomic>
#include <chrono>
#include <condition_variable>
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
    httplib::Client* pollClient_ = nullptr;  ///< Guarded by ``mutex_``; stopped on shutdown.

    std::thread poller_;
    std::thread worker_;
};

}  // namespace midicomp
