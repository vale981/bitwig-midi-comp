#pragma once

#include <filesystem>

namespace midicomp {

/** User preferences kept across restarts. */
struct Settings {
    float uiScale = 1.0f;         ///< Factor on top of the display's scale.
    float laneHeight = 0.0f;      ///< Take lane height in unscaled pixels; 0 fits the window.
    float previewLeadMs = 10.0f;  ///< Preview messages are sent this much early.
    /** Before a preview, remind that the clip's automation lanes must be deleted. */
    bool automationReminder = true;

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
