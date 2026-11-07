#include "comp_store.hpp"

#include <algorithm>
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

std::optional<SavedComp> readHeader(const fs::path& path) {
    const auto text = readFile(path);
    if (!text)
        return std::nullopt;
    try {
        const json j = json::parse(*text);
        return SavedComp{
            .path = path,
            .project = j.at("project").get<std::string>(),
            .clip = j.at("clip").get<std::string>(),
            .session = j.at("session").get<std::string>(),
            .takes = j.at("takes").get<int>(),
            .savedAt = j.at("savedAt").get<std::int64_t>(),
        };
    } catch (const json::exception&) {
        return std::nullopt;
    }
}

}  // namespace

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
                     const std::int64_t savedAt) {
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
        if (auto header = readHeader(entry.path());
            header && header->project == project && header->clip == clip)
            same.push_back(std::move(*header));
    }
    if (same.size() <= kKeepPerClip)
        return;
    std::ranges::sort(same, std::ranges::greater{}, &SavedComp::savedAt);
    for (std::size_t i = kKeepPerClip; i < same.size(); ++i)
        fs::remove(same[i].path, ec);
}

}  // namespace midicomp
