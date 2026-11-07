#pragma once

#include <cstdint>
#include <filesystem>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace midicomp {

/** A comp saved on disk. */
struct SavedComp {
    std::filesystem::path path;
    std::string project;
    std::string clip;
    std::string session;  ///< Identifier of the session that saved it.
    int takes = 0;
    std::int64_t savedAt = 0;  ///< Seconds since the Unix epoch.
    std::string fingerprint;   ///< Fingerprint of the comp's notes, see the extension's API.
    std::string name;          ///< Name given in the companion, or empty.

    /** Returns the name, or the clip label for an unnamed comp. */
    [[nodiscard]] const std::string& title() const { return name.empty() ? clip : name; }
};

/**
 * Local store of comps exported by the extension, so they survive restarts.
 *
 * Each comp session is saved to its own file, ``<project>__<clip>__<session>.json``, containing a
 * small header and the document from ``GET /api/v1/export``. Older files of the same clip are
 * pruned so that at most :member:`kKeepPerClip` remain.
 *
 * Methods may be called from different threads.
 */
class CompStore {
  public:
    static constexpr std::size_t kKeepPerClip = 10;

    /**
     * Args:
     *     dir: directory for the comp files; created on the first save.
     */
    explicit CompStore(std::filesystem::path dir);

    /**
     * Returns the platform's data directory for comps: ``$XDG_DATA_HOME/midicomp/comps`` (or
     * ``~/.local/share/midicomp/comps``) on Linux, ``~/Library/Application Support/midicomp/comps``
     * on macOS, ``%APPDATA%\\midicomp\\comps`` on Windows.
     */
    static std::filesystem::path defaultDir();

    [[nodiscard]] const std::filesystem::path& dir() const { return dir_; }

    /**
     * Saves an exported comp, replacing the earlier save of the same session.
     *
     * Args:
     *     session: identifier of the comp session.
     *     exportJson: body of ``GET /api/v1/export``.
     *     savedAt: time of saving, seconds since the Unix epoch.
     *     name: name given to the comp, or empty.
     *
     * Returns:
     *     Whether the comp was written.
     */
    bool save(std::string_view session, std::string_view exportJson, std::int64_t savedAt,
              std::string_view name = {});

    /** Returns all saved comps, newest first. Unreadable files are skipped. */
    [[nodiscard]] std::vector<SavedComp> list() const;

    /**
     * Returns the newest comp saved for a clip by another session.
     *
     * Args:
     *     project: project name.
     *     clip: clip label.
     *     excludeSession: the current session, whose own saves are not offered.
     */
    [[nodiscard]] std::optional<SavedComp> newestFor(std::string_view project,
                                                     std::string_view clip,
                                                     std::string_view excludeSession) const;

    /**
     * Returns the comp to offer for a session: the newest comp saved for the clip (by title),
     * else the newest comp of the project whose notes the clip holds (same fingerprint: a
     * renamed clip, or a copy of an arranger clip, whose titles the API does not expose).
     *
     * Args:
     *     project: project name.
     *     clip: clip label.
     *     fingerprint: fingerprint of the clip's content; empty matches nothing.
     *     excludeSession: the current session, whose own saves are not offered.
     */
    [[nodiscard]] std::optional<SavedComp> offerFor(std::string_view project, std::string_view clip,
                                                    std::string_view fingerprint,
                                                    std::string_view excludeSession) const;

    /**
     * Returns the export document of a saved comp, for ``POST /api/v1/import``.
     *
     * Args:
     *     comp: an entry from :meth:`list`.
     */
    [[nodiscard]] std::optional<std::string> load(const SavedComp& comp) const;

  private:
    void prune(std::string_view project, std::string_view clip) const;

    std::filesystem::path dir_;
    mutable std::mutex mutex_;
};

/**
 * Turns text into a portable file name component.
 *
 * Args:
 *     text: any text.
 *
 * Returns:
 *     ``text`` with characters other than letters, digits, ``-`` and ``_`` replaced by ``_``,
 *     at most 60 characters long.
 */
std::string fileComponent(std::string_view text);

}  // namespace midicomp
