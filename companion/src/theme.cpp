#include "theme.hpp"

#include <algorithm>
#include <array>
#include <charconv>
#include <cstdlib>
#include <fstream>
#include <sstream>

namespace midicomp {

namespace {

std::string_view trim(std::string_view s) {
    const auto first = s.find_first_not_of(" \t\r\n");
    if (first == std::string_view::npos)
        return {};
    const auto last = s.find_last_not_of(" \t\r\n");
    return s.substr(first, last - first + 1);
}

std::optional<std::string> readFile(const std::filesystem::path& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in)
        return std::nullopt;
    std::stringstream buffer;
    buffer << in.rdbuf();
    return buffer.str();
}

/** Splits ``"6.1.2"`` into ``{6, 1, 2}``; non-numeric parts become 0. */
std::vector<int> versionKey(const std::string& name) {
    std::vector<int> key;
    std::string_view rest = name;
    while (!rest.empty()) {
        const auto dot = rest.find('.');
        const auto part = rest.substr(0, dot);
        int value = 0;
        std::from_chars(part.data(), part.data() + part.size(), value);
        key.push_back(value);
        if (dot == std::string_view::npos)
            break;
        rest.remove_prefix(dot + 1);
    }
    return key;
}

}  // namespace

Color Color::mix(const Color& other, const float t) const {
    return {r + (other.r - r) * t, g + (other.g - g) * t, b + (other.b - b) * t,
            a + (other.a - a) * t};
}

std::optional<Color> parseColor(std::string_view text) {
    text = trim(text);
    if (text.size() != 7 && text.size() != 9)
        return std::nullopt;
    if (text.front() != '#')
        return std::nullopt;

    std::array<float, 4> channels{0.0f, 0.0f, 0.0f, 1.0f};
    for (std::size_t i = 0; i < (text.size() - 1) / 2; ++i) {
        unsigned value = 0;
        const char* begin = text.data() + 1 + i * 2;
        const auto [end, ec] = std::from_chars(begin, begin + 2, value, 16);
        if (ec != std::errc{} || end != begin + 2)
            return std::nullopt;
        channels[i] = static_cast<float>(value) / 255.0f;
    }
    return Color{channels[0], channels[1], channels[2], channels[3]};
}

std::filesystem::path Theme::defaultEditorDir() {
#ifdef _WIN32
    const char* home = std::getenv("USERPROFILE");
#else
    const char* home = std::getenv("HOME");
#endif
    return std::filesystem::path(home != nullptr ? home : ".") / ".bitwig-theme-editor";
}

Theme::Sources Theme::locate(const std::filesystem::path& editorDir) {
    namespace fs = std::filesystem;
    Sources sources;

    bool globalTheme = true;
    if (const auto config = readFile(editorDir / "bte_config.txt")) {
        std::istringstream lines(*config);
        for (std::string line; std::getline(lines, line);) {
            const auto eq = line.find('=');
            if (eq == std::string::npos)
                continue;
            if (trim(std::string_view(line).substr(0, eq)) == "global_theme")
                globalTheme = trim(std::string_view(line).substr(eq + 1)) == "true";
        }
    }

    std::optional<fs::path> newest;
    std::vector<int> newestKey;
    std::error_code ec;
    for (const auto& entry : fs::directory_iterator(editorDir / "versions", ec)) {
        if (!entry.is_directory() || !fs::exists(entry.path() / "default.bte"))
            continue;
        const auto key = versionKey(entry.path().filename().string());
        if (!newest || key > newestKey) {
            newest = entry.path();
            newestKey = key;
        }
    }

    if (newest)
        sources.files.push_back(*newest / "default.bte");
    const fs::path override =
        globalTheme || !newest ? editorDir / "theme.bte" : *newest / "theme.bte";
    if (fs::exists(override, ec))
        sources.files.push_back(override);
    return sources;
}

Theme Theme::load(const Sources& sources) {
    Theme theme;
    for (const auto& file : sources.files) {
        if (const auto text = readFile(file))
            theme.merge(*text);
    }
    return theme;
}

void Theme::merge(const std::string_view text) {
    std::string_view rest = text;
    while (!rest.empty()) {
        const auto newline = rest.find('\n');
        const auto line = trim(rest.substr(0, newline));
        rest = newline == std::string_view::npos ? std::string_view{} : rest.substr(newline + 1);

        if (line.empty() || line.starts_with("//"))
            continue;
        // Names may contain ':' or '.', so split at the last ": ".
        const auto sep = line.rfind(": ");
        if (sep == std::string_view::npos)
            continue;
        if (const auto color = parseColor(line.substr(sep + 2)))
            colors_[std::string(trim(line.substr(0, sep)))] = *color;
    }
}

Color Theme::get(const std::initializer_list<std::string_view> names, const Color fallback) const {
    for (const auto name : names) {
        const auto it = colors_.find(std::string(name));
        if (it != colors_.end() && it->second.a > 0.0f)
            return it->second;
    }
    return fallback;
}

}  // namespace midicomp
