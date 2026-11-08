#include "midi_input.hpp"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cmath>
#include <fstream>
#include <nlohmann/json.hpp>
#include <ranges>

#if __has_include(<rtmidi/RtMidi.h>)
#include <rtmidi/RtMidi.h>
#else
#include <RtMidi.h>
#endif

namespace midicomp {

namespace {

constexpr std::int64_t kReconnectMicros = 2'000'000;
/** Settings value for "the user closed the port": do not open one automatically. */
constexpr std::string_view kNone = "-";

std::string lower(std::string_view text) {
    std::string out(text);
    std::ranges::transform(
        out, out.begin(), [](const unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return out;
}

bool isVirtual(std::string_view name) {
    const std::string l = lower(name);
    return l.find("midi through") != std::string::npos || l.find("rtmidi") != std::string::npos ||
           l.find("virmidi") != std::string::npos || l.find("bitwig") != std::string::npos;
}

}  // namespace

std::int64_t monotonicMicros() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

double TimestampAnchor::add(const double deltaSeconds, const std::int64_t arrivalMicros) {
    if (anchor_)
        elapsed_ += std::max(0.0, deltaSeconds);
    const auto candidate = arrivalMicros - static_cast<std::int64_t>(std::llround(elapsed_ * 1e6));
    if (!anchor_ || candidate < *anchor_)
        anchor_ = candidate;
    return elapsed_;
}

std::int64_t TimestampAnchor::place(const double elapsedSeconds) const {
    return anchor_.value_or(0) + static_cast<std::int64_t>(std::llround(elapsedSeconds * 1e6));
}

void TimestampAnchor::reset() {
    elapsed_ = 0.0;
    anchor_.reset();
}

std::string playedJson(const std::span<const MidiEvent> events, const std::int64_t nowMicros) {
    nlohmann::json list = nlohmann::json::array();
    for (const MidiEvent& e : events)
        list.push_back({e.timeMicros, e.status, e.data1, e.data2});
    return nlohmann::json{{"now", nowMicros}, {"events", std::move(list)}}.dump();
}

std::string portKey(const std::string_view name) {
    // Strip a trailing " <client>:<port>".
    const auto space = name.find_last_of(' ');
    if (space == std::string_view::npos)
        return std::string(name);
    const std::string_view tail = name.substr(space + 1);
    const auto colon = tail.find(':');
    const auto digits = [](const std::string_view s) {
        return !s.empty() &&
               std::ranges::all_of(s, [](const unsigned char c) { return std::isdigit(c) != 0; });
    };
    if (colon == std::string_view::npos || !digits(tail.substr(0, colon)) ||
        !digits(tail.substr(colon + 1)))
        return std::string(name);
    return std::string(name.substr(0, space));
}

std::optional<std::size_t> choosePort(const std::vector<std::string>& ports,
                                      const std::string_view preferred) {
    if (!preferred.empty()) {
        for (std::size_t i = 0; i < ports.size(); ++i)
            if (portKey(ports[i]) == preferred)
                return i;
        return std::nullopt;
    }
    for (std::size_t i = 0; i < ports.size(); ++i)
        if (!isVirtual(ports[i]))
            return i;
    return std::nullopt;
}

MidiInput::MidiInput(std::filesystem::path settingsFile, std::function<void()> onNote)
    : settingsFile_(std::move(settingsFile)), onNote_(std::move(onNote)) {
    try {
        in_ = std::make_unique<RtMidiIn>(RtMidi::UNSPECIFIED, "MIDI Comp");
        in_->ignoreTypes(true, true, true);
        in_->setCallback(&MidiInput::onMessage, this);
    } catch (const RtMidiError& e) {
        in_.reset();
        error_ = e.getMessage();
    }
}

MidiInput::~MidiInput() {
    if (in_) {
        in_->closePort();
        in_->cancelCallback();
    }
}

std::vector<std::string> MidiInput::ports() {
    std::vector<std::string> names;
    if (!in_)
        return names;
    try {
        const unsigned count = in_->getPortCount();
        names.reserve(count);
        for (unsigned i = 0; i < count; ++i)
            names.push_back(in_->getPortName(i));
    } catch (const RtMidiError& e) {
        const std::scoped_lock lock(mutex_);
        error_ = e.getMessage();
    }
    return names;
}

bool MidiInput::open(const std::string& name) {
    if (!in_)
        return false;
    const auto names = ports();
    const auto index = std::ranges::find(names, name);
    if (index == names.end())
        return false;
    try {
        in_->closePort();
        {
            const std::scoped_lock lock(mutex_);
            anchor_.reset();
            pending_.clear();
            portName_.clear();
        }
        in_->openPort(static_cast<unsigned>(index - names.begin()), "Keyboard");
        const std::scoped_lock lock(mutex_);
        portName_ = name;
        error_.clear();
    } catch (const RtMidiError& e) {
        const std::scoped_lock lock(mutex_);
        error_ = e.getMessage();
        return false;
    }
    savePreference(portKey(name));
    return true;
}

void MidiInput::close() {
    if (in_)
        in_->closePort();
    {
        const std::scoped_lock lock(mutex_);
        portName_.clear();
        pending_.clear();
    }
    savePreference(kNone);
}

void MidiInput::reconnect() {
    if (!in_)
        return;
    const std::int64_t now = monotonicMicros();
    std::string open;
    {
        const std::scoped_lock lock(mutex_);
        if (now - lastReconnect_ < kReconnectMicros)
            return;
        lastReconnect_ = now;
        open = portName_;
    }
    const auto names = ports();
    if (!open.empty()) {
        // RtMidi does not report unplugged devices; notice it from the port list.
        if (std::ranges::find(names, open) != names.end())
            return;
        in_->closePort();
        const std::scoped_lock lock(mutex_);
        portName_.clear();
        error_ = "Keyboard disconnected: " + open;
    }
    const auto preferred = loadPreference().value_or("");
    if (preferred == kNone)
        return;
    if (const auto index = choosePort(names, preferred))
        this->open(names[*index]);
}

std::string MidiInput::portName() const {
    const std::scoped_lock lock(mutex_);
    return portName_;
}

std::string MidiInput::error() const {
    const std::scoped_lock lock(mutex_);
    return error_;
}

std::vector<MidiEvent> MidiInput::take() {
    const std::scoped_lock lock(mutex_);
    std::vector<MidiEvent> events;
    events.reserve(pending_.size());
    for (const Pending& p : pending_)
        events.push_back(MidiEvent{.timeMicros = anchor_.place(p.elapsed),
                                   .status = p.status,
                                   .data1 = p.data1,
                                   .data2 = p.data2});
    pending_.clear();
    return events;
}

void MidiInput::onMessage(const double delta, std::vector<unsigned char>* message, void* self) {
    if (message != nullptr && self != nullptr)
        static_cast<MidiInput*>(self)->receive(delta, *message);
}

void MidiInput::receive(const double delta, const std::vector<unsigned char>& message) {
    const std::int64_t arrival = monotonicMicros();
    bool note = false;
    {
        const std::scoped_lock lock(mutex_);
        // Every message advances the driver clock, notes or not.
        const double elapsed = anchor_.add(delta, arrival);
        const auto type = message.empty() ? 0 : message[0] & 0xF0;
        // Notes, and the control messages takes keep: CC (the hi-hat pedal is CC 4), channel
        // pressure (two bytes) and pitch bend.
        const bool threeBytes = type == 0x80 || type == 0x90 || type == 0xB0 || type == 0xE0;
        if ((threeBytes && message.size() >= 3) || (type == 0xD0 && message.size() >= 2)) {
            pending_.push_back(Pending{.elapsed = elapsed,
                                       .status = message[0],
                                       .data1 = message[1],
                                       .data2 = threeBytes ? message[2] : std::uint8_t{0}});
            note = type == 0x80 || type == 0x90;
        }
    }
    if (note) {
        lastNote_ = arrival;
        if (onNote_)
            onNote_();
    }
}

void MidiInput::savePreference(const std::string_view key) const {
    std::error_code ec;
    std::filesystem::create_directories(settingsFile_.parent_path(), ec);
    std::ofstream out(settingsFile_);
    if (out)
        out << key << '\n';
}

std::optional<std::string> MidiInput::loadPreference() const {
    std::ifstream in(settingsFile_);
    if (!in)
        return std::nullopt;
    std::string line;
    std::getline(in, line);
    return line;
}

}  // namespace midicomp
