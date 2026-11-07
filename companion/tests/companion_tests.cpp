#include "api_client.hpp"
#include "palette.hpp"
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
"overdub":false,"pending":true,"truncated":false,"passes":2,"singlePass":true},
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
    CHECK(!s.session->overdub && s.session->passes == 2 && s.session->singlePass);
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
    };
    for (const auto& [name, test] : tests) {
        const int before = failures;
        test();
        std::printf("%-12s %s\n", name, failures == before ? "ok" : "FAILED");
    }
    return failures == 0 ? 0 : 1;
}
