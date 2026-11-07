#include "api_client.hpp"
#include "comp_store.hpp"
#include "midi_input.hpp"
#include "palette.hpp"
#include "settings.hpp"
#include "snap.hpp"
#include "state.hpp"
#include "theme.hpp"

#include <cstdio>
#include <filesystem>
#include <fstream>
#include <functional>
#include <string>
#include <utility>
#include <vector>

namespace {

namespace fs = std::filesystem;
using namespace midicomp;

int failures = 0;

#define CHECK(cond)                                                                                \
    do {                                                                                           \
        if (!(cond)) {                                                                             \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond);          \
            ++failures;                                                                            \
        }                                                                                          \
    } while (false)

void write(const fs::path& path, const std::string& text) {
    fs::create_directories(path.parent_path());
    std::ofstream(path) << text;
}

/** A fresh temporary directory, removed on destruction. */
struct TempDir {
    fs::path path;
    explicit TempDir(const std::string& name)
        : path(fs::temp_directory_path() / ("midicomp-test-" + name)) {
        fs::remove_all(path);
        fs::create_directories(path);
    }
    ~TempDir() { fs::remove_all(path); }
};

// Output of the extension's StateJson for one take, one segment and a link.
constexpr const char* kState = R"({"v":7,"stepsPerBeat":128,"barSteps":512,"length":1024,
"launcherSel":"Keys / Take 1","arrangerSel":null,"canUndoEdit":true,
"canRevert":false,"message":"ok",
"session":{"source":"launcher","label":"Keys / Comp","available":true,"recording":false,
"pending":true,"truncated":false,"passes":2,"singlePass":true},
"log":["12:00:00  Recording started"],
"takes":[{"id":1,"name":"Keys / Take 1","source":"launcher","length":1024,
"notes":[[0,60,64,0.8,0,-1],[512,67,32.5,0.5,1,-1]]}],
"segments":[[0,1024,1]],"comp":[[0,60,64,0.8,0,1]]})";

void testParseState() {
    const State s = parseState(kState);
    CHECK(s.version == 7);
    CHECK(s.stepsPerBeat == 128 && s.barSteps == 512 && s.length == 1024);
    CHECK(s.launcherSel == "Keys / Take 1");
    CHECK(!s.arrangerSel.has_value());
    CHECK(s.canUndoEdit && !s.canRevert);
    CHECK(s.session.has_value() && s.session->pending && s.session->label == "Keys / Comp");
    CHECK(s.session->passes == 2 && s.session->singlePass);
    CHECK(s.log.size() == 1 && s.log[0].ends_with("Recording started"));
    CHECK(s.takes.size() == 1 && s.takes[0].notes.size() == 2);
    CHECK(s.takes[0].notes[1].muted && s.takes[0].notes[1].duration == 32.5);
    CHECK(s.segments.size() == 1 && s.segments[0].to == 1024 && s.segments[0].take == 1);
    CHECK(s.comp.size() == 1 && s.comp[0].take == 1);
    CHECK(s.findTake(1) != nullptr && s.findTake(2) == nullptr);
    CHECK(s.takeIndex(1) == 0);

    const auto [low, high] = s.keyRange();
    CHECK(low <= 60 && high >= 67 && high - low >= 12);

    bool threw = false;
    try {
        (void)parseState("{\"v\":1}");
    } catch (const std::exception&) {
        threw = true;
    }
    CHECK(threw);
}

void testReply() {
    CHECK(parseReply(R"({"ok":true,"message":"done"})").ok);
    const Reply bad = parseReply("not json");
    CHECK(!bad.ok && bad.message == "not json");
}

void testSnap() {
    CHECK(snapSteps(Snap::Bar, 128, 512) == 512);
    CHECK(snapSteps(Snap::Sixteenth, 128, 512) == 32);
    CHECK(snapSteps(Snap::Off, 128, 512) == 1);

    // Drag within bar 2 selects bar 2.
    CHECK(snapRange(600, 700, 512, 4096) == std::optional(std::pair{512, 1024}));
    // Drag across a bar line selects both bars, in either direction.
    CHECK(snapRange(1100, 400, 512, 4096) == std::optional(std::pair{0, 1536}));
    // Ending exactly on a bar line does not include the next bar.
    CHECK(snapRange(0, 512, 512, 4096) == std::optional(std::pair{0, 512}));
    // A click selects the cell under the pointer.
    CHECK(snapRange(700, 700, 512, 4096) == std::optional(std::pair{512, 1024}));
    // With snapping off a click selects nothing, a drag is exact.
    CHECK(!snapRange(700, 700, 1, 4096).has_value());
    CHECK(snapRange(700, 750, 1, 4096) == std::optional(std::pair{700, 750}));
    // Clamped to the comp.
    CHECK(snapRange(3900, 4000, 512, 4000) == std::optional(std::pair{3584, 4000}));
    CHECK(!snapRange(5000, 5100, 512, 4000).has_value());
}

void testQueryEncoding() {
    CHECK(encodeQueryValue("abc-_.~09") == "abc-_.~09");
    CHECK(encodeQueryValue("a b&c=d") == "a%20b%26c%3Dd");
    CHECK(encodeQueryValue("\xC3\xA4") == "%C3%A4");
}

void testEndpoint() {
    const TempDir dir("endpoint");
    const auto file = dir.path / "endpoint.json";
    CHECK(!readEndpoint(file).has_value());
    write(file, R"({"url":"http://127.0.0.1:8765","token":"abc","apiVersion":1,"pid":1})");
    const auto endpoint = readEndpoint(file);
    CHECK(endpoint.has_value() && endpoint->url == "http://127.0.0.1:8765" &&
          endpoint->token == "abc");
    write(file, "{broken");
    CHECK(!readEndpoint(file).has_value());
}

void testColors() {
    const auto c = parseColor(" #ff8000 ");
    CHECK(c.has_value() && c->r == 1.0f && c->b == 0.0f && c->a == 1.0f);
    const auto translucent = parseColor("#00000080");
    CHECK(translucent.has_value() && translucent->a > 0.49f && translucent->a < 0.51f);
    CHECK(!parseColor("#fff").has_value());
    CHECK(!parseColor("false").has_value());
    CHECK(!parseColor("#gg0000").has_value());
}

void testThemeMerge() {
    Theme theme;
    theme.merge("// comment\nGradient: false\nWindow background: #101010\n"
                "Background color of the modular environment.: #202020\n"
                "Accent: #00000000\nA: b: #303030\n");
    CHECK(theme.size() == 4);
    const Color fallback{0.5f, 0.5f, 0.5f, 1.0f};
    CHECK(theme.get({"Missing", "Window background"}, fallback).r < 0.07f);
    CHECK(theme.get({"Background color of the modular environment."}, fallback).r > 0.12f);
    // Transparent entries are skipped.
    CHECK(theme.get({"Accent"}, fallback) == fallback);
    CHECK(theme.get({"A: b"}, fallback).r > 0.18f);
    theme.merge("Window background: #ffffff\n");
    CHECK(theme.get({"Window background"}, fallback).r == 1.0f);
}

void testThemeLocate() {
    const TempDir dir("theme");
    write(dir.path / "versions" / "4.3.10" / "default.bte", "Window background: #010101\n");
    write(dir.path / "versions" / "6.1" / "default.bte",
          "Window background: #020202\nGrey 1: #111111\n");
    write(dir.path / "versions" / "6.1" / "theme.bte", "Window background: #030303\n");
    write(dir.path / "theme.bte", "Window background: #040404\n");

    write(dir.path / "bte_config.txt", "bitwig_path=/x/bitwig.jar\nglobal_theme=true\n");
    auto sources = Theme::locate(dir.path);
    CHECK(sources.files.size() == 2);
    CHECK(sources.files.front() == dir.path / "versions" / "6.1" / "default.bte");
    CHECK(sources.files.back() == dir.path / "theme.bte");
    const Color fallback{};
    Theme theme = Theme::load(sources);
    CHECK(theme.get({"Window background"}, fallback) == parseColor("#040404"));
    CHECK(theme.get({"Grey 1"}, fallback) == parseColor("#111111"));

    write(dir.path / "bte_config.txt", "global_theme=false\n");
    sources = Theme::locate(dir.path);
    CHECK(sources.files.back() == dir.path / "versions" / "6.1" / "theme.bte");

    const TempDir empty("theme-empty");
    CHECK(Theme::locate(empty.path).files.empty());
}

void testPalette() {
    // An empty theme falls back to Bitwig's stock colors.
    const Palette stock = makePalette(Theme{});
    CHECK(stock.takes.size() >= 6);
    CHECK(stock.take(1) != stock.take(2));
    CHECK(stock.take(1) == stock.take(1 + static_cast<int>(stock.takes.size())));

    // Duplicate panel colors collapse; the palette is topped up to stay distinguishable.
    Theme theme;
    theme.merge("Panel Blue: #907aa9\nPanel Purple: #907aa9\nPanel Turquoise: #907aa9\n"
                "Window background: #faf4ed\nDefault text: #575279\n");
    const Palette p = makePalette(theme);
    CHECK(p.takes.size() >= 6);
    for (std::size_t i = 0; i < p.takes.size(); ++i)
        for (std::size_t j = i + 1; j < p.takes.size(); ++j)
            CHECK(p.takes[i] != p.takes[j]);
    CHECK(p.windowBg ==
          ImGui::ColorConvertFloat4ToU32(ImVec4(250 / 255.0f, 244 / 255.0f, 237 / 255.0f, 1)));

    CHECK((withAlpha(IM_COL32(10, 20, 30, 255), 0.0f) & IM_COL32_A_MASK) == 0);
}

std::string exportDoc(const std::string& clip, int takes) {
    std::string doc = R"({"format":1,"project":"Song","clip":")" + clip +
                      R"(","source":"arranger","stepSize":0.0078125,"takes":[)";
    for (int i = 0; i < takes; ++i)
        doc += (i > 0 ? "," : "") + std::string(R"({"name":"Pass","notes":[]})");
    return doc + R"(],"segments":[]})";
}

void testCompStore() {
    const TempDir dir("store");
    CompStore store(dir.path / "comps");
    CHECK(store.list().empty());

    CHECK(store.save("s1", exportDoc("Keys (arranger)", 2), 100));
    CHECK(store.save("s1", exportDoc("Keys (arranger)", 3), 110));  // same session: replaced
    CHECK(store.save("s2", exportDoc("Keys (arranger)", 4), 200));
    CHECK(store.save("s3", exportDoc("Bass", 1), 150));
    CHECK(!store.save("s4", "not json", 300));

    const auto all = store.list();
    CHECK(all.size() == 3);
    CHECK(all.front().savedAt == 200 && all.front().takes == 4);

    const auto newest = store.newestFor("Song", "Keys (arranger)", "s2");
    CHECK(newest.has_value() && newest->session == "s1" && newest->takes == 3);
    CHECK(!store.newestFor("Other song", "Keys (arranger)", "").has_value());

    const auto document = store.load(*newest);
    CHECK(document.has_value() && document->find("\"format\":1") != std::string::npos);

    for (int i = 0; i < 15; ++i)
        store.save("p" + std::to_string(i), exportDoc("Pad", 2), 1000 + i);
    int pads = 0;
    for (const auto& comp : store.list())
        pads += comp.clip == "Pad" ? 1 : 0;
    CHECK(pads == static_cast<int>(CompStore::kKeepPerClip));

    // A copy of a clip is recognized by the fingerprint of the comp it holds.
    store.save("v1", R"({"format":1,"project":"Song","clip":"Keys / Take A","fingerprint":"abc",
                         "takes":[{},{}],"segments":[]})",
               5000);
    const auto copy = store.offerFor("Song", "Keys / Take A copy", "abc", "v2");
    CHECK(copy.has_value() && copy->session == "v1" && copy->fingerprint == "abc");
    CHECK(!store.offerFor("Song", "Drums", "", "v2").has_value());
    CHECK(store.offerFor("Song", "Bass", "", "v2")->session == "s3");  // by clip, as before
    // The title comes first, even when another clip's comp has the same fingerprint.
    CHECK(store.offerFor("Song", "Bass", "abc", "v2")->session == "s3");
    CHECK(!store.offerFor("Song", "Keys / Take A copy", "abc", "v1").has_value());

    // Names are kept with the comp; unnamed comps are titled by their clip.
    CHECK(store.save("n1", exportDoc("Lead", 2), 6000, "Chorus take"));
    const auto named = store.newestFor("Song", "Lead", "");
    CHECK(named && named->name == "Chorus take" && named->title() == "Chorus take");
    CHECK(store.newestFor("Song", "Bass", "")->title() == "Bass");

    CHECK(fileComponent("Keys / Take 1 (ä)") == "Keys___Take_1_____");  // ä is two bytes
    CHECK(fileComponent("") == "untitled");
}

void testPreview() {
    const State s = parseState(R"json({"v":3,"stepsPerBeat":128,"barSteps":512,"length":1024,
        "project":"P","launcherSel":null,"arrangerSel":null,"canUndoEdit":false,
        "canRevert":false,"message":"","log":[],
        "session":{"source":"arranger","label":"Keys (arranger)","available":false,
                   "recording":false,"pending":false,"truncated":false,"clipLength":-1,
                   "passes":2,"singlePass":false,"recordingPass":0,"collecting":true,
                   "track":"Keys","preview":[{"pass":3,"notes":[[10,40,64,0.5,0,-1]]}]},
        "takes":[],"segments":[],"comp":[]})json");
    CHECK(s.session && s.session->collecting && s.session->track == "Keys");
    CHECK(s.preview().size() == 1 && s.preview()[0].pass == 3);
    CHECK(s.preview()[0].notes.size() == 1 && s.preview()[0].notes[0].key == 40);
    CHECK(s.keyRange().first <= 40);  // provisional notes count for the key range
}

void testSettings() {
    const TempDir dir("settings");
    const fs::path file = dir.path / "settings.json";
    CHECK(loadSettings(file) == Settings{});  // missing file: defaults
    CHECK(saveSettings(file, Settings{.uiScale = 1.5f, .laneHeight = 90.0f}));
    const Settings read = loadSettings(file);
    CHECK(read.uiScale == 1.5f && read.laneHeight == 90.0f);
    write(file, R"({"uiScale": 99, "laneHeight": -3})");
    CHECK(loadSettings(file) == Settings{});  // out of range: defaults
}

void testMidi() {
    // The message delivered with the least delay sets the anchor; earlier ones are corrected.
    TimestampAnchor anchor;
    const double first = anchor.add(0.0, 1'000'300);   // 300 µs late
    const double second = anchor.add(0.5, 1'500'050);  // 50 µs late
    const double third = anchor.add(0.25, 1'750'900);  // 900 µs late
    CHECK(anchor.place(first) == 1'000'050);
    CHECK(anchor.place(second) == 1'500'050);
    CHECK(anchor.place(third) == 1'750'050);
    anchor.reset();
    CHECK(anchor.place(anchor.add(3.0, 7)) == 7);  // the first delta after a reset is ignored

    const std::vector<MidiEvent> events{
        {.timeMicros = 1'234'567'890'123, .status = 0x90, .data1 = 60, .data2 = 100},
        {.timeMicros = 1'234'567'990'123, .status = 0x80, .data1 = 60, .data2 = 0}};
    CHECK(
        playedJson(events, 1'234'568'000'000) ==
        R"({"events":[[1234567890123,144,60,100],[1234567990123,128,60,0]],"now":1234568000000})");

    CHECK(portKey("USB Axiom 61:USB Axiom 61 MIDI 1 20:0") == "USB Axiom 61:USB Axiom 61 MIDI 1");
    CHECK(portKey("Axiom 61") == "Axiom 61");
    CHECK(portKey("Port 2") == "Port 2");

    const std::vector<std::string> ports{"Midi Through:Midi Through Port-0 14:0",
                                         "USB Axiom 61:USB Axiom 61 MIDI 1 24:0",
                                         "Launchpad:Launchpad MIDI 1 28:0"};
    CHECK(choosePort(ports, "") == 1u);
    CHECK(choosePort(ports, "Launchpad:Launchpad MIDI 1") == 2u);
    CHECK(!choosePort(ports, "Gone").has_value());
    CHECK(!choosePort({"Midi Through:Midi Through Port-0 14:0"}, "").has_value());
}

}  // namespace

int main() {
    const std::vector<std::pair<const char*, std::function<void()>>> tests{
        {"parseState", testParseState},
        {"reply", testReply},
        {"snap", testSnap},
        {"query", testQueryEncoding},
        {"endpoint", testEndpoint},
        {"colors", testColors},
        {"themeMerge", testThemeMerge},
        {"themeLocate", testThemeLocate},
        {"palette", testPalette},
        {"compStore", testCompStore},
        {"midi", testMidi},
        {"preview", testPreview},
        {"settings", testSettings},
    };
    for (const auto& [name, test] : tests) {
        const int before = failures;
        test();
        std::printf("%-12s %s\n", name, failures == before ? "ok" : "FAILED");
    }
    return failures == 0 ? 0 : 1;
}
