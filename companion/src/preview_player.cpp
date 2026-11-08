#include "preview_player.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <fstream>
#include <regex>
#include <sstream>

#if __has_include(<rtmidi/RtMidi.h>)
#include <rtmidi/RtMidi.h>
#else
#include <RtMidi.h>
#endif

namespace midicomp {

namespace {

constexpr int kPulsesPerBeat = 24;
/** Pulses used for the tempo and phase estimate. */
constexpr std::size_t kRecentPulses = 48;
/** Without a pulse for this long, the clock counts as stopped. */
constexpr std::int64_t kClockTimeoutMicros = 250'000;

/**
 * Returns the name of the VirMIDI copy the preview port is connected to (Linux), as RtMidi lists
 * it, so Bitwig's MIDI clock sent to that device can be read back.
 */
std::optional<std::string> clockPortName() {
    std::ifstream in("/proc/asound/seq/clients");
    if (!in)
        return std::nullopt;
    std::stringstream buffer;
    buffer << in.rdbuf();
    const std::string text = buffer.str();
    // Our client "MIDI Comp" with its port "Preview", then "Connecting To: <client>:<port>".
    const std::regex preview(
        R"re(Client\s+\d+\s*:\s*"MIDI Comp"[^\n]*\n(?:\s+[^\n]*\n)*?\s+Port\s+\d+\s*:\s*"Preview"[^\n]*\n\s+Connecting To:\s*(\d+):)re");
    std::smatch m;
    if (!std::regex_search(text, m, preview))
        return std::nullopt;
    const std::regex client("Client\\s+" + m[1].str() + "\\s*:\\s*\"([^\"]+)\"");
    std::smatch c;
    if (!std::regex_search(text, c, client))
        return std::nullopt;
    return c[1].str();
}

}  // namespace

// --- ClockFollower

void ClockFollower::start(const std::int64_t /*timeMicros*/) {
    startBeat_ = 0.0;
    pulses_ = 0;
    recent_.clear();
    running_ = true;
}

void ClockFollower::resume(const std::int64_t /*timeMicros*/) {
    pulses_ = 0;
    recent_.clear();
    running_ = true;
}

void ClockFollower::stop() {
    running_ = false;
    recent_.clear();
}

void ClockFollower::songPosition(const int sixteenths) {
    startBeat_ = sixteenths / 4.0;
    pulses_ = 0;
    recent_.clear();
}

void ClockFollower::pulse(const std::int64_t timeMicros) {
    if (!running_)
        return;
    recent_.emplace_back(timeMicros, startBeat_ + static_cast<double>(pulses_) / kPulsesPerBeat);
    ++pulses_;
    lastPulse_ = timeMicros;
    if (recent_.size() > kRecentPulses)
        recent_.erase(recent_.begin());
}

bool ClockFollower::running(const std::int64_t nowMicros) const {
    return running_ && !recent_.empty() && nowMicros - lastPulse_ < kClockTimeoutMicros;
}

std::optional<double> ClockFollower::beatAt(const std::int64_t timeMicros) const {
    if (recent_.size() < 4)
        return std::nullopt;
    // Tempo from the span of recent pulses, phase as the mean of (beat - slope * time).
    const auto& [t0, b0] = recent_.front();
    const auto& [t1, b1] = recent_.back();
    if (t1 <= t0)
        return std::nullopt;
    const double slope = (b1 - b0) / static_cast<double>(t1 - t0);
    double intercept = 0.0;
    for (const auto& [t, b] : recent_)
        intercept += b - slope * static_cast<double>(t - t1);
    intercept /= static_cast<double>(recent_.size());
    return intercept + slope * static_cast<double>(timeMicros - t1);
}

// --- PreviewEvents

void PreviewEvents::build(const State& state) {
    events_.clear();
    const double end = state.length > 0 ? state.length : 1e12;
    for (const Note& n : state.comp) {
        if (n.muted)
            continue;
        const auto channel = static_cast<std::uint8_t>(n.channel & 0x0F);
        const auto key = static_cast<std::uint8_t>(std::clamp(n.key, 0, 127));
        const auto velocity = static_cast<std::uint8_t>(
            std::clamp(static_cast<int>(std::lround(n.velocity * 127.0)), 1, 127));
        const double on = n.x;
        const double off = std::min(end, on + std::max(1.0, n.duration));
        events_.push_back(
            PreviewEvent{.step = on,
                         .order = 2,
                         .bytes = {static_cast<std::uint8_t>(0x90 | channel), key, velocity},
                         .size = 3});
        events_.push_back(PreviewEvent{.step = off,
                                       .order = 0,
                                       .bytes = {static_cast<std::uint8_t>(0x80 | channel), key, 0},
                                       .size = 3});
    }
    for (const ControlPoint& c : state.compControls) {
        const bool twoBytes = (c.status & 0xF0) == 0xD0;
        events_.push_back(PreviewEvent{.step = static_cast<double>(c.x),
                                       .order = 1,
                                       .bytes = {static_cast<std::uint8_t>(c.status),
                                                 static_cast<std::uint8_t>(c.data1),
                                                 static_cast<std::uint8_t>(c.data2)},
                                       .size = static_cast<std::uint8_t>(twoBytes ? 2 : 3)});
    }
    std::ranges::stable_sort(events_, [](const PreviewEvent& a, const PreviewEvent& b) {
        return a.step != b.step ? a.step < b.step : a.order < b.order;
    });
}

std::vector<PreviewEvent> PreviewEvents::between(const double from, const double to) const {
    std::vector<PreviewEvent> result;
    const auto first = std::ranges::upper_bound(events_, from, {}, &PreviewEvent::step);
    for (auto it = first; it != events_.end() && it->step <= to; ++it)
        result.push_back(*it);
    return result;
}

std::vector<PreviewEvent> PreviewEvents::controllersAt(const double step) const {
    std::map<int, PreviewEvent> latest;
    for (const PreviewEvent& e : events_) {
        if (e.step > step)
            break;
        if (e.order != 1)
            continue;
        const int type = e.bytes[0] & 0xF0;
        latest[type == 0xB0 ? e.bytes[0] << 8 | e.bytes[1] : e.bytes[0] << 8 | 0xFF] = e;
    }
    std::vector<PreviewEvent> result;
    result.reserve(latest.size());
    for (const auto& [controller, e] : latest)
        result.push_back(e);
    return result;
}

// --- PreviewPlayer

PreviewPlayer::PreviewPlayer(std::function<Playhead()> playhead) : playhead_(std::move(playhead)) {
    try {
        out_ = std::make_unique<RtMidiOut>(RtMidi::UNSPECIFIED, "MIDI Comp");
        out_->openVirtualPort("Preview");
    } catch (const RtMidiError& e) {
        out_.reset();
        error_ = "No virtual MIDI port on this system: " + e.getMessage();
    }
    if (out_)
        thread_ = std::thread([this] { run(); });
}

PreviewPlayer::~PreviewPlayer() {
    stopping_ = true;
    if (thread_.joinable())
        thread_.join();
    if (clockIn_) {
        clockIn_->closePort();
        clockIn_->cancelCallback();
    }
}

std::string PreviewPlayer::error() const {
    const std::scoped_lock lock(mutex_);
    return error_;
}

void PreviewPlayer::setComp(const State& state) {
    auto events = std::make_shared<PreviewEvents>();
    events->build(state);
    const std::scoped_lock lock(mutex_);
    events_ = std::move(events);
    compChanged_ = true;
}

std::string PreviewPlayer::timingSource() const {
    const std::scoped_lock lock(mutex_);
    return timing_;
}

void PreviewPlayer::openClockInput() {
    // Linux: read Bitwig's MIDI clock from the VirMIDI copy the preview port is connected to.
    const auto name = clockPortName();
    if (!name)
        return;
    try {
        if (!clockIn_) {
            clockIn_ = std::make_unique<RtMidiIn>(RtMidi::UNSPECIFIED, "MIDI Comp clock");
            clockIn_->ignoreTypes(true, false, true);  // keep timing messages
            clockIn_->setCallback(
                [](const double delta, std::vector<unsigned char>* message, void* self) {
                    if (message != nullptr)
                        static_cast<PreviewPlayer*>(self)->onClock(delta, *message);
                },
                this);
        }
        if (clockIn_->isPortOpen())
            return;
        for (unsigned i = 0; i < clockIn_->getPortCount(); ++i)
            if (clockIn_->getPortName(i).starts_with(*name)) {
                clockIn_->openPort(i, "Clock");
                return;
            }
    } catch (const RtMidiError&) {
        clockIn_.reset();
    }
}

void PreviewPlayer::onClock(const double delta, const std::vector<unsigned char>& message) {
    const std::int64_t arrival = monotonicMicros();
    const std::scoped_lock lock(mutex_);
    const std::int64_t time = clockAnchor_.place(clockAnchor_.add(delta, arrival));
    if (message.empty())
        return;
    switch (message[0]) {
    case 0xF8:
        clock_.pulse(time);
        break;
    case 0xFA:
        clock_.start(time);
        break;
    case 0xFB:
        clock_.resume(time);
        break;
    case 0xFC:
        clock_.stop();
        break;
    case 0xF2:
        if (message.size() >= 3)
            clock_.songPosition(message[1] | message[2] << 7);
        break;
    default:
        break;
    }
}

std::optional<double> PreviewPlayer::stepAt(const std::int64_t timeMicros, const Playhead& p) {
    if (!p.originBeat || p.stepSize <= 0.0)
        return std::nullopt;
    std::optional<double> step;
    {
        const std::scoped_lock lock(mutex_);
        if (clock_.running(timeMicros - leadMicros_.load()))
            if (const auto beat = clock_.beatAt(timeMicros)) {
                step = (*beat - *p.originBeat) / p.stepSize;
                timing_ = "MIDI clock";
            }
        if (!step && p.transportPlaying && p.anchor) {
            step = p.anchor->second +
                   static_cast<double>(timeMicros - p.anchor->first) * 1e-6 * p.stepsPerSecond;
            timing_ = "transport";
        }
        if (!step)
            timing_.clear();
    }
    // The arranger loop: positions past its end lie at its start again.
    if (step && p.loop && p.loop->second > p.loop->first && *step >= p.loop->second)
        step = p.loop->first + std::fmod(*step - p.loop->first, p.loop->second - p.loop->first);
    return step;
}

void PreviewPlayer::send(const PreviewEvent& e) {
    try {
        out_->sendMessage(e.bytes.data(), e.size);
    } catch (const RtMidiError&) {
        return;
    }
    const int type = e.bytes[0] & 0xF0;
    const int note = (e.bytes[0] & 0x0F) << 8 | e.bytes[1];
    if (type == 0x90)
        ++sounding_[note];
    else if (type == 0x80 && sounding_.contains(note) && --sounding_[note] <= 0)
        sounding_.erase(note);
}

void PreviewPlayer::allNotesOff() {
    for (const auto& [note, count] : sounding_) {
        const std::array<std::uint8_t, 3> off{static_cast<std::uint8_t>(0x80 | note >> 8),
                                              static_cast<std::uint8_t>(note & 0x7F), 0};
        try {
            for (int i = 0; i < count; ++i)
                out_->sendMessage(off.data(), off.size());
        } catch (const RtMidiError&) {
        }
    }
    sounding_.clear();
}

void PreviewPlayer::run() {
    using namespace std::chrono_literals;
    auto lastClockSearch = std::chrono::steady_clock::now() - 10s;
    while (!stopping_) {
        std::this_thread::sleep_for(1ms);
        if (std::chrono::steady_clock::now() - lastClockSearch > 2s) {
            lastClockSearch = std::chrono::steady_clock::now();
            openClockInput();
        }
        if (!enabled_) {
            allNotesOff();
            lastStep_ = -1.0;
            continue;
        }
        const Playhead p = playhead_();
        const auto step = stepAt(monotonicMicros() + leadMicros_.load(), p);
        if (!step) {
            allNotesOff();
            lastStep_ = -1.0;
            continue;
        }
        std::shared_ptr<const PreviewEvents> shared;
        bool changed = false;
        {
            const std::scoped_lock lock(mutex_);
            changed = std::exchange(compChanged_, false);
            shared = events_;
        }
        const PreviewEvents& events = *shared;
        if (changed) {
            // The comp changed: end what sounds, the new comp takes over from here.
            allNotesOff();
            for (const PreviewEvent& e : events.controllersAt(*step))
                send(e);
            lastStep_ = *step;
            continue;
        }
        const double grace = p.stepsPerSecond * 0.1;  // jumps beyond a tenth of a second
        if (lastStep_ < 0.0 || *step < lastStep_ - grace || *step > lastStep_ + grace) {
            const bool wrapped = lastStep_ >= 0.0 && p.loop && *step < lastStep_ &&
                                 lastStep_ > p.loop->second - grace &&
                                 *step < p.loop->first + grace;
            if (wrapped) {
                // The loop wrapped: play up to its end, then from its start.
                for (const PreviewEvent& e : events.between(lastStep_, p.loop->second))
                    send(e);
                allNotesOff();
                for (const PreviewEvent& e : events.controllersAt(p.loop->first))
                    send(e);
                for (const PreviewEvent& e : events.between(p.loop->first, *step))
                    send(e);
            } else {
                // Started, sought or jumped: silence, then the controllers' state here.
                allNotesOff();
                for (const PreviewEvent& e : events.controllersAt(*step))
                    send(e);
            }
            lastStep_ = *step;
            continue;
        }
        if (*step > lastStep_) {
            for (const PreviewEvent& e : events.between(lastStep_, *step))
                send(e);
            lastStep_ = *step;
        }
    }
    allNotesOff();
}

}  // namespace midicomp
