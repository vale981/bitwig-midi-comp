#include "state.hpp"

#include <algorithm>
#include <nlohmann/json.hpp>

namespace midicomp {

namespace {

using nlohmann::json;

std::optional<std::string> optionalString(const json& j, const char* key) {
    const auto it = j.find(key);
    if (it == j.end() || it->is_null())
        return std::nullopt;
    return it->get<std::string>();
}

// Note arrays are [x, key, durationSteps, velocity, muted, takeId].
std::vector<Note> parseNotes(const json& array) {
    std::vector<Note> notes;
    notes.reserve(array.size());
    for (const auto& n : array) {
        notes.push_back(Note{
            .x = n.at(0).get<int>(),
            .key = n.at(1).get<int>(),
            .duration = n.at(2).get<double>(),
            .velocity = n.at(3).get<double>(),
            .muted = n.at(4).get<int>() != 0,
            .take = n.at(5).get<int>(),
        });
    }
    return notes;
}

}  // namespace

const Take* State::findTake(const int id) const {
    const auto it = std::ranges::find(takes, id, &Take::id);
    return it == takes.end() ? nullptr : &*it;
}

int State::takeIndex(const int id) const {
    const auto it = std::ranges::find(takes, id, &Take::id);
    return it == takes.end() ? -1 : static_cast<int>(it - takes.begin());
}

std::pair<int, int> State::keyRange() const {
    int low = 127;
    int high = 0;
    for (const auto& take : takes) {
        for (const auto& note : take.notes) {
            low = std::min(low, note.key);
            high = std::max(high, note.key);
        }
    }
    if (low > high) {
        low = 60;
        high = 72;
    }
    // Widen symmetrically to at least an octave, staying inside 0-127.
    while (high - low < 12) {
        if (low > 0)
            --low;
        if (high - low < 12 && high < 127)
            ++high;
    }
    return {low, high};
}

State parseState(const std::string_view text) {
    const json j = json::parse(text);
    State s;
    s.version = j.at("v").get<long long>();
    s.stepsPerBeat = std::max(1, j.at("stepsPerBeat").get<int>());
    s.barSteps = std::max(1, j.at("barSteps").get<int>());
    s.length = j.at("length").get<int>();
    s.project = j.value("project", std::string{});
    s.launcherSel = optionalString(j, "launcherSel");
    s.arrangerSel = optionalString(j, "arrangerSel");
    s.canUndoEdit = j.value("canUndoEdit", false);
    s.canRevert = j.value("canRevert", false);
    s.message = j.value("message", std::string{});

    if (const auto it = j.find("session"); it != j.end() && !it->is_null()) {
        s.session = Session{
            .source = it->at("source").get<std::string>(),
            .label = it->at("label").get<std::string>(),
            .available = it->at("available").get<bool>(),
            .recording = it->at("recording").get<bool>(),
            .pending = it->at("pending").get<bool>(),
            .truncated = it->at("truncated").get<bool>(),
            .clipLength = it->value("clipLength", -1),
            .passes = it->at("passes").get<int>(),
            .singlePass = it->value("singlePass", false),
            .recordingPass = it->value("recordingPass", 0),
        };
    }

    const auto& takes = j.at("takes");
    s.takes.reserve(takes.size());
    for (const auto& t : takes) {
        s.takes.push_back(Take{
            .id = t.at("id").get<int>(),
            .name = t.at("name").get<std::string>(),
            .source = t.at("source").get<std::string>(),
            .length = t.at("length").get<int>(),
            .notes = parseNotes(t.at("notes")),
        });
    }

    const auto& segments = j.at("segments");
    s.segments.reserve(segments.size());
    for (const auto& seg : segments) {
        s.segments.push_back(Segment{
            .from = seg.at(0).get<int>(),
            .to = seg.at(1).get<int>(),
            .take = seg.at(2).get<int>(),
        });
    }

    s.comp = parseNotes(j.at("comp"));
    if (const auto it = j.find("log"); it != j.end() && it->is_array())
        s.log = it->get<std::vector<std::string>>();
    return s;
}

Reply parseReply(const std::string_view text) {
    try {
        const json j = json::parse(text);
        return Reply{.ok = j.value("ok", false), .message = j.value("message", std::string{})};
    } catch (const json::exception&) {
        return Reply{.ok = false, .message = std::string(text)};
    }
}

}  // namespace midicomp
