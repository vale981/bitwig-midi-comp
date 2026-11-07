#pragma once

#include <filesystem>

namespace midicomp {

/** User preferences kept across restarts. */
struct Settings {
    float uiScale = 1.0f;     ///< Factor on top of the display's scale.
    float laneHeight = 0.0f;  ///< Take lane height in unscaled pixels; 0 fits the window.

    bool operator==(const Settings&) const = default;
};

/**
 * Reads settings; missing or invalid entries keep their defaults.
 *
 * Args:
 *     path: the settings file (JSON).
 */
Settings loadSettings(const std::filesystem::path& path);

/**
 * Writes settings, creating the directory if needed.
 *
 * Args:
 *     path: the settings file (JSON).
 *     settings: values to write.
 *
 * Returns:
 *     Whether the file was written.
 */
bool saveSettings(const std::filesystem::path& path, const Settings& settings);

}  // namespace midicomp
