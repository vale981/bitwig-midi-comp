#pragma once

#include <filesystem>
#include <initializer_list>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace midicomp {

/** An RGBA color with components in 0-1. */
struct Color {
    float r = 0.0f;
    float g = 0.0f;
    float b = 0.0f;
    float a = 1.0f;

    bool operator==(const Color&) const = default;

    /** Returns this color with opacity ``alpha``. */
    [[nodiscard]] Color withAlpha(float alpha) const { return {r, g, b, alpha}; }

    /** Returns the linear blend ``(1 - t) * this + t * other``, including alpha. */
    [[nodiscard]] Color mix(const Color& other, float t) const;

    /** Returns the relative luminance (sRGB weights, no gamma correction). */
    [[nodiscard]] float luminance() const { return 0.2126f * r + 0.7152f * g + 0.0722f * b; }
};

/**
 * Parses ``#rrggbb`` or ``#rrggbbaa``.
 *
 * Args:
 *     text: color literal, surrounding whitespace allowed.
 *
 * Returns:
 *     The color, or ``std::nullopt`` for anything else.
 */
std::optional<Color> parseColor(std::string_view text);

/**
 * Named colors loaded from Bitwig Theme Editor files (``.bte``).
 *
 * A ``.bte`` file contains ``Name: #rrggbb[aa]`` lines and ``//`` comments. Later files override
 * earlier ones.
 */
class Theme {
  public:
    /** Files that make up the active theme, base first. */
    struct Sources {
        std::vector<std::filesystem::path> files;
    };

    /**
     * Locates the active theme of the Bitwig Theme Editor.
     *
     * Reads ``bte_config.txt`` in ``editorDir``. The base is ``versions/<v>/default.bte`` of the
     * newest version directory; the override is ``theme.bte`` in ``editorDir`` when
     * ``global_theme=true``, otherwise ``versions/<v>/theme.bte``. Missing files are skipped.
     *
     * Args:
     *     editorDir: usually ``~/.bitwig-theme-editor``.
     */
    static Sources locate(const std::filesystem::path& editorDir);

    /** Returns ``~/.bitwig-theme-editor``. */
    static std::filesystem::path defaultEditorDir();

    /**
     * Loads and merges ``sources``; unreadable files are ignored.
     *
     * Args:
     *     sources: files in increasing priority.
     */
    static Theme load(const Sources& sources);

    /**
     * Merges the colors of one ``.bte`` document into this theme.
     *
     * Args:
     *     text: file content.
     */
    void merge(std::string_view text);

    /**
     * Returns the first defined color among ``names``, or ``fallback``.
     *
     * Fully transparent entries count as undefined, since themes use them to hide elements.
     */
    [[nodiscard]] Color get(std::initializer_list<std::string_view> names, Color fallback) const;

    [[nodiscard]] std::size_t size() const { return colors_.size(); }

  private:
    std::unordered_map<std::string, Color> colors_;
};

}  // namespace midicomp
