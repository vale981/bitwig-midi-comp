#include "api_client.hpp"

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
            commandInFlight_ = !command.onDocument;
        }
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
