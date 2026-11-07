#include "settings.hpp"

#include <fstream>
#include <nlohmann/json.hpp>
#include <sstream>

namespace midicomp {

Settings loadSettings(const std::filesystem::path& path) {
    Settings settings;
    std::ifstream in(path);
    if (!in)
        return settings;
    std::stringstream buffer;
    buffer << in.rdbuf();
    try {
        const auto j = nlohmann::json::parse(buffer.str());
        if (const float scale = j.value("uiScale", 1.0f); scale >= 0.5f && scale <= 4.0f)
            settings.uiScale = scale;
        if (const float height = j.value("laneHeight", 0.0f); height >= 0.0f && height <= 1000.0f)
            settings.laneHeight = height;
    } catch (const nlohmann::json::exception&) {
    }
    return settings;
}

bool saveSettings(const std::filesystem::path& path, const Settings& settings) {
    std::error_code ec;
    std::filesystem::create_directories(path.parent_path(), ec);
    std::ofstream out(path);
    if (!out)
        return false;
    out << nlohmann::json{{"uiScale", settings.uiScale}, {"laneHeight", settings.laneHeight}}.dump(
               1)
        << '\n';
    return static_cast<bool>(out);
}

}  // namespace midicomp
