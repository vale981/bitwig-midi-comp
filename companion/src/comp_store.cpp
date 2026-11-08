#include "comp_store.hpp"

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <nlohmann/json.hpp>
#include <sstream>

namespace midicomp {

namespace {

namespace fs = std::filesystem;
using nlohmann::json;

std::optional<std::string> readFile(const fs::path& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in)
        return std::nullopt;
    std::stringstream buffer;
    buffer << in.rdbuf();
    return buffer.str();
}

fs::path envPath(const char* name) {
    const char* value = std::getenv(name);
    return value != nullptr && *value != '\0' ? fs::path(value) : fs::path();
}

/** Returns a key for a JSON value (64-bit FNV-1a of its text), stable across runs. */
std::string keyOf(const json& value) {
    static constexpr char kHex[] = "0123456789abcdef";
    std::uint64_t hash = 14695981039346656037ULL;
    for (const char c : value.dump()) {
        hash ^= static_cast<unsigned char>(c);
        hash *= 1099511628211ULL;
    }
    std::string key(16, '0');
    for (int i = 15; i >= 0; --i, hash >>= 4)
        key[static_cast<std::size_t>(i)] = kHex[hash & 0xF];
    return key;
}

/** Describes which takes a comp's segments use, e.g. "takes 1, 3 · 4 cuts". */
std::string summarizeCuts(const json& segments) {
    std::vector<int> takes;
    int cuts = 0;
    for (const auto& s : segments) {
        if (!s.is_array() || s.size() < 3)
            continue;
        ++cuts;
        const int take = s[2].get<int>() + 1;
        if (std::ranges::find(takes, take) == takes.end())
            takes.push_back(take);
    }
    if (cuts == 0)
        return "empty";
    std::ranges::sort(takes);
    std::string text = takes.size() == 1 ? "take " : "takes ";
    for (std::size_t i = 0; i < takes.size(); ++i)
        text += (i > 0 ? ", " : "") + std::to_string(takes[i]);
    return text + " \u00b7 " + std::to_string(cuts) + (cuts == 1 ? " cut" : " cuts");
}

std::optional<json> readJson(const fs::path& path) {
    const auto text = readFile(path);
    if (!text)
        return std::nullopt;
    try {
        return json::parse(*text);
    } catch (const json::exception&) {
        return std::nullopt;
    }
}

bool writeJson(const fs::path& path, const json& value) {
    const fs::path tmp = fs::path(path).concat(".tmp");
    {
        std::ofstream out(tmp, std::ios::binary);
        if (!out)
            return false;
        out << value.dump();
        if (!out)
            return false;
    }
    std::error_code ec;
    fs::rename(tmp, path, ec);
    return !ec;
}

std::optional<SavedComp> readHeader(const fs::path& path) {
    const auto text = readFile(path);
    if (!text)
        return std::nullopt;
    try {
        const json j = json::parse(*text);
        const json& comp = j.at("comp");
        return SavedComp{
            .path = path,
            .project = j.at("project").get<std::string>(),
            .clip = j.at("clip").get<std::string>(),
            .session = j.at("session").get<std::string>(),
            .takes = j.at("takes").get<int>(),
            .savedAt = j.at("savedAt").get<std::int64_t>(),
            .fingerprint = j.value("fingerprint", std::string{}),
            .name = j.value("name", std::string{}),
            .takesKey = keyOf(comp.at("takes")),
            .cutsKey = keyOf(comp.value("segments", json::array())),
            .cutsSummary = summarizeCuts(comp.value("segments", json::array())),
        };
    } catch (const json::exception&) {
        return std::nullopt;
    }
}

}  // namespace

bool CompStore::rename(const SavedComp& comp, const std::string_view name) {
    const std::scoped_lock lock(mutex_);
    auto doc = readJson(comp.path);
    if (!doc || !doc->is_object())
        return false;
    (*doc)["name"] = std::string(name);
    return writeJson(comp.path, *doc);
}

bool CompStore::remove(const SavedComp& comp) {
    const std::scoped_lock lock(mutex_);
    std::error_code ec;
    return fs::remove(comp.path, ec);
}

std::string CompStore::recordingName(const std::string_view takesKey) const {
    const std::scoped_lock lock(mutex_);
    const auto names = readJson(dir_ / "recordings.json");
    if (!names || !names->is_object())
        return {};
    return names->value(std::string(takesKey), std::string{});
}

bool CompStore::setRecordingName(const std::string_view takesKey, const std::string_view name) {
    const std::scoped_lock lock(mutex_);
    std::error_code ec;
    fs::create_directories(dir_, ec);
    json names = readJson(dir_ / "recordings.json").value_or(json::object());
    if (!names.is_object())
        names = json::object();
    if (name.empty())
        names.erase(std::string(takesKey));
    else
        names[std::string(takesKey)] = std::string(name);
    return writeJson(dir_ / "recordings.json", names);
}

std::vector<RecordingSet> groupByRecordings(const std::vector<SavedComp>& comps) {
    std::vector<SavedComp> sorted = comps;
    std::ranges::sort(sorted, std::ranges::greater{}, &SavedComp::savedAt);
    std::vector<RecordingSet> sets;
    for (const SavedComp& comp : sorted) {
        auto set = std::ranges::find(sets, comp.takesKey, &RecordingSet::takesKey);
        if (set == sets.end()) {
            sets.push_back(
                RecordingSet{.takesKey = comp.takesKey, .comps = {}, .firstSaved = comp.savedAt});
            set = sets.end() - 1;
        }
        set->firstSaved = std::min(set->firstSaved, comp.savedAt);
        auto same = std::ranges::find(set->comps, comp.cutsKey, &SavedComp::cutsKey);
        if (same == set->comps.end())
            set->comps.push_back(comp);
        else if (same->name.empty() && !comp.name.empty())
            *same = comp;  // an older save of the same cut that has a name
    }
    return sets;
}

std::optional<std::string> takesOnly(const std::string_view exportJson) {
    try {
        json doc = json::parse(exportJson);
        const json& takes = doc.at("takes");
        if (takes.empty())
            doc["segments"] = json::array();
        else
            doc["segments"] = json::array({json::array({0, takes[0].value("length", 0), 0})});
        return doc.dump();
    } catch (const json::exception&) {
        return std::nullopt;
    }
}

std::string fileComponent(const std::string_view text) {
    std::string out;
    for (const char c : text) {
        const auto u = static_cast<unsigned char>(c);
        const bool keep = (u >= 'a' && u <= 'z') || (u >= 'A' && u <= 'Z') ||
                          (u >= '0' && u <= '9') || u == '-' || u == '_';
        out.push_back(keep ? c : '_');
        if (out.size() == 60)
            break;
    }
    return out.empty() ? "untitled" : out;
}

CompStore::CompStore(fs::path dir) : dir_(std::move(dir)) {
}

fs::path CompStore::defaultDir() {
#if defined(_WIN32)
    fs::path base = envPath("APPDATA");
    if (base.empty())
        base = envPath("USERPROFILE");
    return base / "midicomp" / "comps";
#elif defined(__APPLE__)
    return envPath("HOME") / "Library" / "Application Support" / "midicomp" / "comps";
#else
    fs::path base = envPath("XDG_DATA_HOME");
    if (base.empty())
        base = envPath("HOME") / ".local" / "share";
    return base / "midicomp" / "comps";
#endif
}

bool CompStore::save(const std::string_view session, const std::string_view exportJson,
                     const std::int64_t savedAt, const std::string_view name) {
    json comp;
    try {
        comp = json::parse(exportJson);
    } catch (const json::exception&) {
        return false;
    }
    if (!comp.is_object() || !comp.contains("takes") || !comp.contains("clip"))
        return false;

    const std::string project = comp.value("project", std::string{});
    const std::string clip = comp.value("clip", std::string{});
    const json document{
        {"project", project},
        {"clip", clip},
        {"session", std::string(session)},
        {"takes", comp.at("takes").size()},
        {"savedAt", savedAt},
        {"fingerprint", comp.value("fingerprint", std::string{})},
        {"name", std::string(name)},
        {"comp", comp},
    };

    const std::scoped_lock lock(mutex_);
    std::error_code ec;
    fs::create_directories(dir_, ec);
    const fs::path file = dir_ / (fileComponent(project) + "__" + fileComponent(clip) + "__" +
                                  fileComponent(session) + ".json");
    const fs::path tmp = fs::path(file).concat(".tmp");
    {
        std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
        if (!out)
            return false;
        out << document.dump();
        if (!out)
            return false;
    }
    fs::rename(tmp, file, ec);
    if (ec)
        return false;
    prune(project, clip);
    return true;
}

std::vector<SavedComp> CompStore::list() const {
    std::vector<SavedComp> comps;
    const std::scoped_lock lock(mutex_);
    std::error_code ec;
    for (const auto& entry : fs::directory_iterator(dir_, ec)) {
        if (entry.path().extension() != ".json")
            continue;
        if (auto header = readHeader(entry.path()))
            comps.push_back(std::move(*header));
    }
    std::ranges::sort(comps, std::ranges::greater{}, &SavedComp::savedAt);
    return comps;
}

std::optional<SavedComp> CompStore::offerFor(const std::string_view project,
                                             const std::string_view clip,
                                             const std::string_view fingerprint,
                                             const std::string_view excludeSession) const {
    const auto comps = list();
    for (const auto& comp : comps)
        if (comp.project == project && comp.clip == clip && comp.session != excludeSession)
            return comp;
    if (!fingerprint.empty())
        for (const auto& comp : comps)
            if (comp.project == project && comp.fingerprint == fingerprint &&
                comp.session != excludeSession)
                return comp;
    return std::nullopt;
}

std::optional<SavedComp> CompStore::newestFor(const std::string_view project,
                                              const std::string_view clip,
                                              const std::string_view excludeSession) const {
    for (auto& comp : list()) {
        if (comp.project == project && comp.clip == clip && comp.session != excludeSession)
            return comp;
    }
    return std::nullopt;
}

std::optional<std::string> CompStore::load(const SavedComp& comp) const {
    const std::scoped_lock lock(mutex_);
    const auto text = readFile(comp.path);
    if (!text)
        return std::nullopt;
    try {
        return json::parse(*text).at("comp").dump();
    } catch (const json::exception&) {
        return std::nullopt;
    }
}

void CompStore::prune(const std::string_view project, const std::string_view clip) const {
    // Called with mutex_ held.
    std::vector<SavedComp> same;
    std::error_code ec;
    for (const auto& entry : fs::directory_iterator(dir_, ec)) {
        if (entry.path().extension() != ".json")
            continue;
        // Named comps are kept; only unnamed saves are pruned.
        if (auto header = readHeader(entry.path());
            header && header->project == project && header->clip == clip && header->name.empty())
            same.push_back(std::move(*header));
    }
    if (same.size() <= kKeepPerClip)
        return;
    std::ranges::sort(same, std::ranges::greater{}, &SavedComp::savedAt);
    for (std::size_t i = kKeepPerClip; i < same.size(); ++i)
        fs::remove(same[i].path, ec);
}

}  // namespace midicomp
