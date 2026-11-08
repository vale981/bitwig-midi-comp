#include "api_client.hpp"

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <fstream>
#include <httplib.h>
#include <nlohmann/json.hpp>
#include <sstream>

namespace midicomp {

namespace {

constexpr int kWaitSeconds = 20;

httplib::Headers authHeaders(const Endpoint& endpoint) {
    return {{"X-Token", endpoint.token}};
}

}  // namespace

double Playhead::stepAt(const std::chrono::steady_clock::time_point now) const {
    if (!playing || step < 0)
        return step;
    const double elapsed = std::chrono::duration<double>(now - at).count();
    // Beyond a few updates without news the transport has likely stopped; do not run away.
    return step + std::clamp(elapsed, 0.0, 0.25) * stepsPerSecond;
}

double PlayheadFollower::position(const Playhead& latest,
                                  const std::chrono::steady_clock::time_point now) {
    const auto extrapolate = [&](const std::chrono::steady_clock::time_point t) {
        const double elapsed = std::chrono::duration<double>(t - anchorTime_).count();
        return anchor_ + (playing_ ? std::clamp(elapsed, 0.0, 0.25) * stepsPerSecond_ : 0.0);
    };
    if (latest.at != seen_) {
        seen_ = latest.at;
        const double predicted = extrapolate(latest.at);
        const double error = latest.step - predicted;
        const bool jump = !latest.playing || !playing_ || latest.step < 0 || anchor_ < 0.0 ||
                          std::abs(error) > 0.1 * std::max(1.0, latest.stepsPerSecond);
        // Within 0.1 s of the prediction: correct a fifth of the difference per update.
        anchor_ = jump ? latest.step : predicted + 0.2 * error;
        anchorTime_ = latest.at;
        playing_ = latest.playing;
        stepsPerSecond_ = latest.stepsPerSecond;
        if (jump)
            shown_ = anchor_;
    }
    if (anchor_ < 0.0)
        return -1.0;
    const double next = extrapolate(now);
    // While playing the line only moves forward; corrections catch up instead of stepping back.
    shown_ = playing_ ? std::max(shown_, next) : next;
    return shown_;
}

std::optional<Playhead> parsePlayhead(const std::string_view json) {
    try {
        const auto j = nlohmann::json::parse(json);
        return Playhead{
            .playing = j.value("playing", false),
            .step = j.value("step", -1),
            .stepsPerSecond = j.value("stepsPerSecond", 0.0),
            .canSeek = j.value("canSeek", false),
            .transportPlaying = j.value("transportPlaying", false),
            .auditioning = j.value("auditioning", false),
            .originBeat = j.contains("originBeat") ? std::optional(j.at("originBeat").get<double>())
                                                   : std::nullopt,
            .stepSize = j.value("stepSize", 0.0),
            .loop = j.contains("loop") ? std::optional(std::pair(j.at("loop").at(0).get<double>(),
                                                                 j.at("loop").at(1).get<double>()))
                                       : std::nullopt,
            .anchor = j.contains("anchor")
                          ? std::optional(std::pair(j.at("anchor").at("us").get<std::int64_t>(),
                                                    j.at("anchor").at("step").get<double>()))
                          : std::nullopt,
            .at = {}};
    } catch (const nlohmann::json::exception&) {
        return std::nullopt;
    }
}

std::filesystem::path defaultEndpointPath() {
#ifdef _WIN32
    const char* home = std::getenv("USERPROFILE");
#else
    const char* home = std::getenv("HOME");
#endif
    return std::filesystem::path(home != nullptr ? home : ".") / ".midicomp" / "endpoint.json";
}

std::optional<Endpoint> readEndpoint(const std::filesystem::path& path) {
    std::ifstream in(path);
    if (!in)
        return std::nullopt;
    std::stringstream buffer;
    buffer << in.rdbuf();
    try {
        const auto j = nlohmann::json::parse(buffer.str());
        Endpoint endpoint{.url = j.at("url").get<std::string>(),
                          .token = j.at("token").get<std::string>()};
        if (endpoint.url.empty() || endpoint.token.empty())
            return std::nullopt;
        return endpoint;
    } catch (const nlohmann::json::exception&) {
        return std::nullopt;
    }
}

std::string encodeQueryValue(const std::string_view value) {
    static constexpr char kHex[] = "0123456789ABCDEF";
    std::string out;
    out.reserve(value.size());
    for (const char c : value) {
        const auto u = static_cast<unsigned char>(c);
        const bool unreserved = (u >= 'A' && u <= 'Z') || (u >= 'a' && u <= 'z') ||
                                (u >= '0' && u <= '9') || u == '-' || u == '_' || u == '.' ||
                                u == '~';
        if (unreserved) {
            out.push_back(c);
        } else {
            out.push_back('%');
            out.push_back(kHex[u >> 4]);
            out.push_back(kHex[u & 0x0F]);
        }
    }
    return out;
}

ApiClient::ApiClient(std::filesystem::path endpointPath, std::function<void()> onChange)
    : endpointPath_(std::move(endpointPath)), onChange_(std::move(onChange)) {
    poller_ = std::thread([this] { pollLoop(); });
    worker_ = std::thread([this] { commandLoop(); });
    playheadPoller_ = std::thread([this] { playheadLoop(); });
}

ApiClient::~ApiClient() {
    {
        // Abort a long poll in progress; the poller checks the flag before starting another.
        const std::scoped_lock lock(mutex_);
        stopping_ = true;
        if (pollClient_ != nullptr)
            pollClient_->stop();
    }
    wake_.notify_all();
    poller_.join();
    worker_.join();
    playheadPoller_.join();
}

bool ApiClient::sleepFor(const std::chrono::milliseconds duration) {
    std::unique_lock lock(mutex_);
    return wake_.wait_for(lock, duration, [this] { return stopping_.load(); });
}

std::shared_ptr<const State> ApiClient::state() const {
    const std::scoped_lock lock(mutex_);
    return state_;
}

ApiClient::Connection ApiClient::connection() const {
    const std::scoped_lock lock(mutex_);
    return connection_;
}

std::string ApiClient::connectionDetail() const {
    const std::scoped_lock lock(mutex_);
    return connectionDetail_;
}

std::optional<Reply> ApiClient::lastReply() const {
    const std::scoped_lock lock(mutex_);
    return lastReply_;
}

Playhead ApiClient::playhead() const {
    const std::scoped_lock lock(mutex_);
    return playhead_;
}

void ApiClient::playheadLoop() {
    using namespace std::chrono_literals;
    std::optional<Endpoint> current;
    std::unique_ptr<httplib::Client> client;
    while (!stopping_) {
        std::optional<Endpoint> endpoint;
        {
            const std::scoped_lock lock(mutex_);
            endpoint = endpoint_;
        }
        if (!pollPlayhead_ || !endpoint) {
            sleepFor(200ms);
            continue;
        }
        if (endpoint != current) {
            client = std::make_unique<httplib::Client>(endpoint->url);
            client->set_connection_timeout(1, 0);
            client->set_read_timeout(1, 0);
            current = endpoint;
        }
        const auto result = client->Get("/api/v1/playhead", authHeaders(*endpoint));
        if (result && result->status == 200) {
            if (auto parsed = parsePlayhead(result->body)) {
                parsed->at = std::chrono::steady_clock::now();
                bool changed = false;
                {
                    const std::scoped_lock lock(mutex_);
                    changed =
                        parsed->playing || playhead_.playing || parsed->step != playhead_.step;
                    playhead_ = *parsed;
                }
                if (changed)
                    notify();
            }
        }
        sleepFor(50ms);
    }
}

bool ApiClient::busy() const {
    const std::scoped_lock lock(mutex_);
    return commandInFlight_ || !commands_.empty();
}

void ApiClient::send(std::string op, std::vector<Param> params, std::string body) {
    {
        const std::scoped_lock lock(mutex_);
        commands_.push_back(Command{.op = std::move(op),
                                    .params = std::move(params),
                                    .body = std::move(body),
                                    .onDocument = {}});
    }
    wake_.notify_all();
    notify();
}

void ApiClient::post(std::string op, std::string body) {
    {
        const std::scoped_lock lock(mutex_);
        commands_.push_back(Command{.op = std::move(op),
                                    .params = {},
                                    .body = std::move(body),
                                    .onDocument = {},
                                    .silent = true});
    }
    wake_.notify_all();
}

void ApiClient::fetch(std::string op, std::function<void(std::optional<std::string>)> done) {
    {
        const std::scoped_lock lock(mutex_);
        commands_.push_back(
            Command{.op = std::move(op), .params = {}, .body = {}, .onDocument = std::move(done)});
    }
    wake_.notify_all();
}

std::optional<Endpoint> ApiClient::currentEndpoint() {
    auto endpoint = readEndpoint(endpointPath_);
    const std::scoped_lock lock(mutex_);
    endpoint_ = endpoint;
    return endpoint;
}

void ApiClient::setConnection(const Connection connection, std::string detail) {
    {
        const std::scoped_lock lock(mutex_);
        if (connection_ == connection && connectionDetail_ == detail)
            return;
        connection_ = connection;
        connectionDetail_ = std::move(detail);
    }
    notify();
}

void ApiClient::notify() const {
    if (onChange_)
        onChange_();
}

void ApiClient::pollLoop() {
    using namespace std::chrono_literals;
    long long version = -1;
    std::optional<Endpoint> previous;

    while (!stopping_) {
        const auto endpoint = currentEndpoint();
        if (!endpoint) {
            setConnection(Connection::Searching,
                          "Waiting for the extension (" + endpointPath_.string() + ")");
            sleepFor(1s);
            continue;
        }
        if (endpoint != previous) {
            // A restarted extension starts counting versions anew.
            version = -1;
            previous = endpoint;
        }

        httplib::Client client(endpoint->url);
        client.set_connection_timeout(2, 0);
        client.set_read_timeout(kWaitSeconds + 10, 0);
        {
            const std::scoped_lock lock(mutex_);
            if (stopping_)
                break;
            pollClient_ = &client;
        }
        const auto result = client.Get("/api/v1/state?since=" + std::to_string(version) +
                                           "&wait=" + std::to_string(kWaitSeconds),
                                       authHeaders(*endpoint));
        {
            const std::scoped_lock lock(mutex_);
            pollClient_ = nullptr;
        }
        if (stopping_)
            break;

        if (!result) {
            setConnection(Connection::Error, "Cannot reach " + endpoint->url + " (" +
                                                 httplib::to_string(result.error()) + ")");
            sleepFor(1s);
            continue;
        }

        switch (result->status) {
        case 200:
            try {
                auto parsed = std::make_shared<const State>(parseState(result->body));
                version = parsed->version;
                {
                    const std::scoped_lock lock(mutex_);
                    state_ = std::move(parsed);
                }
                setConnection(Connection::Connected, "Connected to " + endpoint->url);
                notify();
            } catch (const std::exception& e) {
                setConnection(Connection::Error, std::string("Invalid state: ") + e.what());
                sleepFor(1s);
            }
            break;
        case 204:
            setConnection(Connection::Connected, "Connected to " + endpoint->url);
            break;
        case 401:
            setConnection(Connection::Error, "Token rejected; waiting for a new endpoint file");
            sleepFor(1s);
            break;
        default:
            setConnection(Connection::Error,
                          "Unexpected HTTP status " + std::to_string(result->status));
            sleepFor(1s);
            break;
        }
    }
}

void ApiClient::commandLoop() {
    while (true) {
        Command command;
        {
            std::unique_lock lock(mutex_);
            wake_.wait(lock, [this] { return stopping_ || !commands_.empty(); });
            if (stopping_)
                return;
            command = std::move(commands_.front());
            commands_.pop_front();
            commandInFlight_ = !command.onDocument && !command.silent;
        }
        if (!command.silent)
            notify();

        std::string path = "/api/v1/" + command.op;
        char separator = '?';
        for (const auto& [name, value] : command.params) {
            path += separator;
            path += encodeQueryValue(name) + "=" + encodeQueryValue(value);
            separator = '&';
        }

        const auto endpoint = readEndpoint(endpointPath_);
        if (command.onDocument) {
            // Document requests are silent: they do not change the status message.
            std::optional<std::string> document;
            if (endpoint) {
                httplib::Client client(endpoint->url);
                client.set_connection_timeout(2, 0);
                client.set_read_timeout(10, 0);
                const auto result = client.Get(path, authHeaders(*endpoint));
                if (result && result->status == 200 &&
                    result->body.find("\"ok\":false") == std::string::npos)
                    document = result->body;
            }
            command.onDocument(std::move(document));
            const std::scoped_lock lock(mutex_);
            commandInFlight_ = false;
            continue;
        }

        if (command.silent) {
            if (endpoint) {
                httplib::Client client(endpoint->url);
                client.set_connection_timeout(2, 0);
                client.set_read_timeout(10, 0);
                client.Post(path, authHeaders(*endpoint), command.body, "application/json");
            }
            continue;
        }

        Reply reply;
        if (!endpoint) {
            reply = Reply{.ok = false, .message = "The extension is not running"};
        } else {
            httplib::Client client(endpoint->url);
            client.set_connection_timeout(2, 0);
            client.set_read_timeout(10, 0);
            const auto result =
                client.Post(path, authHeaders(*endpoint), command.body,
                            command.body.empty() ? "text/plain" : "application/json");
            if (!result)
                reply = Reply{.ok = false,
                              .message = "Request failed: " + httplib::to_string(result.error())};
            else
                reply = parseReply(result->body);
        }

        {
            const std::scoped_lock lock(mutex_);
            lastReply_ = std::move(reply);
            commandInFlight_ = false;
        }
        notify();
    }
}

}  // namespace midicomp
