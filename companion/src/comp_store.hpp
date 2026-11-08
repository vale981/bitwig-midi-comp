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
    std::string takesKey;      ///< Identifies the recordings (all takes' notes).
    std::string cutsKey;       ///< Identifies the cuts (segments) over those takes.
    std::string cutsSummary;   ///< Which takes the cuts use, e.g. "takes 1, 3 · 4 cuts".

    /** Returns the name, or the clip label for an unnamed comp. */
    [[nodiscard]] const std::string& title() const { return name.empty() ? clip : name; }
};

/**
 * Local store of comps exported by the extension, so they survive restarts.
 *
 * Each comp session is saved to its own file, ``<project>__<clip>__<session>.json``, containing a
 * small header and the document from ``GET /api/v1/export``. Older unnamed files of the same
 * clip are pruned so that at most :member:`kKeepPerClip` remain; named comps are kept.
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

    /**
     * Renames a saved comp.
     *
     * Args:
     *     comp: an entry from :meth:`list`.
     *     name: the new name; empty removes it.
     *
     * Returns:
     *     Whether the file was rewritten.
     */
    bool rename(const SavedComp& comp, std::string_view name);

    /**
     * Deletes a saved comp.
     *
     * Args:
     *     comp: an entry from :meth:`list`.
     */
    bool remove(const SavedComp& comp);

    /** Returns the name given to a set of recordings, or an empty string. */
    [[nodiscard]] std::string recordingName(std::string_view takesKey) const;

    /**
     * Names a set of recordings (kept in ``recordings.json``).
     *
     * Args:
     *     takesKey: :member:`SavedComp::takesKey` of the set.
     *     name: the name; empty removes it.
     */
    bool setRecordingName(std::string_view takesKey, std::string_view name);

  private:
    void prune(std::string_view project, std::string_view clip) const;

    std::filesystem::path dir_;
    mutable std::mutex mutex_;
};

/** Saved comps that share their recordings: the same takes, cut in different ways. */
struct RecordingSet {
    std::string takesKey;
    std::vector<SavedComp> comps;  ///< One per distinct cut, newest first.
    std::int64_t firstSaved = 0;   ///< When the first comp of these recordings was saved.

    /** Returns the newest comp, which describes the set (clip, takes, time). */
    [[nodiscard]] const SavedComp& newest() const { return comps.front(); }
};

/**
 * Groups saved comps by their recordings. Saves of the same cut (autosaves, restores) collapse
 * into one: the newest named one, else the newest.
 *
 * Args:
 *     comps: saved comps, in any order.
 *
 * Returns:
 *     The recording sets, newest first.
 */
std::vector<RecordingSet> groupByRecordings(const std::vector<SavedComp>& comps);

/**
 * Returns an export document with the cuts replaced by the first take over its whole length, to
 * restore only the recordings of a saved comp.
 *
 * Args:
 *     exportJson: a document from ``GET /api/v1/export``.
 *
 * Returns:
 *     The changed document, or ``std::nullopt`` if it cannot be read.
 */
std::optional<std::string> takesOnly(std::string_view exportJson);

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
