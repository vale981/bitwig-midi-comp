#include "palette.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <string_view>

namespace midicomp {

namespace {

// Stock Bitwig (dark) values used when the theme does not define a color.
constexpr Color kBitwigBackground{0.149f, 0.149f, 0.149f, 1.0f};     // #262626
constexpr Color kBitwigTimelineLight{0.196f, 0.196f, 0.196f, 1.0f};  // #323232
constexpr Color kBitwigTimelineDark{0.165f, 0.165f, 0.165f, 1.0f};   // #2a2a2a
constexpr Color kBitwigHeader{0.239f, 0.239f, 0.239f, 1.0f};         // #3d3d3d
constexpr Color kBitwigText{0.80f, 0.80f, 0.80f, 1.0f};              // #cccccc
constexpr Color kBitwigAccent{1.0f, 0.529f, 0.075f, 1.0f};           // #ff8713
constexpr Color kBitwigButton{0.286f, 0.286f, 0.286f, 1.0f};         // #494949
constexpr Color kBitwigPressed{0.353f, 0.353f, 0.353f, 1.0f};        // #5a5a5a

struct PanelColor {
    std::string_view name;
    Color fallback;
};

// Ordered so that neighbouring takes get distinct hues.
constexpr std::array<PanelColor, 9> kPanelColors{{
    {"Panel Blue", {0.298f, 0.553f, 0.859f, 1.0f}},
    {"Panel Orange", {1.0f, 0.529f, 0.075f, 1.0f}},
    {"Panel Green", {0.333f, 0.722f, 0.361f, 1.0f}},
    {"Panel Purple", {0.608f, 0.431f, 0.839f, 1.0f}},
    {"Panel Red", {0.898f, 0.325f, 0.294f, 1.0f}},
    {"Panel Mint", {0.271f, 0.773f, 0.647f, 1.0f}},
    {"Panel Yellow", {0.878f, 0.753f, 0.314f, 1.0f}},
    {"Panel Turquoise", {0.247f, 0.714f, 0.788f, 1.0f}},
    {"Panel Lime", {0.639f, 0.788f, 0.302f, 1.0f}},
}};

ImU32 u32(const Color& c) {
    return ImGui::ColorConvertFloat4ToU32(ImVec4(c.r, c.g, c.b, c.a));
}
ImVec4 v4(const Color& c) {
    return {c.r, c.g, c.b, c.a};
}

/** Returns ``color`` composited over ``background`` (removes translucency). */
Color over(const Color& color, const Color& background) {
    const Color opaque = background.mix(color.withAlpha(1.0f), color.a);
    return opaque.withAlpha(1.0f);
}

/** Moves ``color`` towards ``text`` until it differs from ``background`` by at least ``minDelta``.
 */
Color ensureContrast(Color color, const Color& background, const Color& text,
                     const float minDelta) {
    for (int i = 0; i < 8 && std::abs(color.luminance() - background.luminance()) < minDelta; ++i)
        color = color.mix(text.withAlpha(color.a), 0.08f);
    return color;
}

}  // namespace

ImU32 withAlpha(const ImU32 color, const float alpha) {
    const auto a = static_cast<ImU32>(std::clamp(alpha, 0.0f, 1.0f) * 255.0f);
    return (color & ~IM_COL32_A_MASK) | (a << IM_COL32_A_SHIFT);
}

ImU32 darken(const ImU32 color, const float amount) {
    ImVec4 c = ImGui::ColorConvertU32ToFloat4(color);
    c.x *= 1.0f - amount;
    c.y *= 1.0f - amount;
    c.z *= 1.0f - amount;
    return ImGui::ColorConvertFloat4ToU32(c);
}

ImU32 Palette::take(const int takeId, const float alpha) const {
    if (takes.empty())
        return withAlpha(accent, alpha);
    const auto index = static_cast<std::size_t>(std::max(0, takeId - 1)) % takes.size();
    return withAlpha(takes[index], alpha);
}

Palette makePalette(const Theme& theme) {
    Palette p;
    const Color window =
        over(theme.get({"Window background", "Grey 2"}, kBitwigBackground), kBitwigBackground);
    const Color text = theme.get({"Default text", "Light Text", "Menu text"}, kBitwigText);
    const Color muted = theme.get({"Medium Light Text", "Subtle Light Text"}, text.withAlpha(0.6f));
    const Color accent = theme.get({"Accent (default)", "Accent"}, kBitwigAccent);

    const Color compBg = over(
        theme.get({"Light Timeline Background", "Dark Timeline Background"}, kBitwigTimelineLight),
        window);
    const Color laneBg =
        over(theme.get({"Dark Timeline Background", "Irrelevant Timeline Background"},
                       kBitwigTimelineDark),
             window);
    const Color header = over(
        theme.get({"Dark Timeline Header Background", "Light Timeline Header Background", "Grey 3"},
                  kBitwigHeader),
        window);

    p.windowBg = u32(window);
    p.compBg = u32(compBg);
    p.laneBg = u32(laneBg);
    p.headerBg = u32(header);
    p.laneSeparator = u32(
        over(theme.get({"Light Separator Line", "Panel Stroke (focused)"}, text.withAlpha(0.25f)),
             laneBg)
            .withAlpha(0.6f));
    p.barLine = u32(theme.get({"Grid Line (Primary)"}, text.withAlpha(0.28f)));
    p.beatLine = u32(theme.get({"Grid Line (Secondary)"}, text.withAlpha(0.12f)));
    p.text = u32(text);
    p.mutedText = u32(muted);
    p.inactiveNote = u32(muted.withAlpha(0.45f));
    p.selectionFill = u32(
        theme.get({"Time Selection Fill", "Header Time Selection Fill"}, accent.withAlpha(0.22f)));
    p.selectionStroke =
        u32(theme.get({"Time Selection Stroke", "Header Time Selection Stroke"}, accent));
    const Color red = theme.get({"Panel Red", "Error Text"}, kPanelColors[4].fallback);
    p.clearFill = u32(red.withAlpha(0.22f));
    p.clearStroke = u32(red);
    p.accent = u32(accent);
    p.playhead = u32(theme.get({"Timeline Playhead"}, text));
    p.live = u32(theme.get({"Panel Green", "Panel Mint"}, kPanelColors[2].fallback));
    p.warning = u32(theme.get({"Panel Yellow", "Panel Orange"}, kPanelColors[6].fallback));
    p.error = u32(theme.get({"Error Text", "Panel Red", "Warning"}, kPanelColors[4].fallback)
                      .withAlpha(1.0f));

    // Themes often reuse one color for several panels; keep only distinct ones.
    std::vector<Color> takeColors;
    for (const auto& panel : kPanelColors) {
        const Color c = theme.get({panel.name}, panel.fallback).withAlpha(1.0f);
        if (std::ranges::find(takeColors, c) == takeColors.end())
            takeColors.push_back(c);
    }
    for (const auto& panel : kPanelColors) {
        if (takeColors.size() >= 6)
            break;
        if (std::ranges::find(takeColors, panel.fallback) == takeColors.end())
            takeColors.push_back(panel.fallback);
    }
    for (const auto& c : takeColors)
        p.takes.push_back(u32(c));
    return p;
}

void applyStyle(const Theme& theme, const float scale) {
    ImGuiStyle& style = ImGui::GetStyle();
    style = ImGuiStyle();

    // Bitwig: flat surfaces, small radii, compact spacing.
    style.WindowRounding = 0.0f;
    style.ChildRounding = 0.0f;
    style.FrameRounding = 3.0f;
    style.PopupRounding = 3.0f;
    style.GrabRounding = 3.0f;
    style.ScrollbarRounding = 3.0f;
    style.TabRounding = 3.0f;
    style.WindowBorderSize = 0.0f;
    style.ChildBorderSize = 0.0f;
    style.PopupBorderSize = 1.0f;
    style.FrameBorderSize = 0.0f;
    style.WindowPadding = ImVec2(10.0f, 8.0f);
    style.FramePadding = ImVec2(8.0f, 4.0f);
    style.ItemSpacing = ImVec2(6.0f, 6.0f);
    style.ItemInnerSpacing = ImVec2(5.0f, 4.0f);
    style.ScrollbarSize = 10.0f;
    style.GrabMinSize = 8.0f;
    style.ScaleAllSizes(scale);

    const Color window =
        over(theme.get({"Window background", "Grey 2"}, kBitwigBackground), kBitwigBackground);
    const Color text = theme.get({"Default text", "Light Text", "Menu text"}, kBitwigText);
    const Color muted = theme.get({"Medium Light Text", "Subtle Light Text"}, text.withAlpha(0.6f));
    const Color accent = theme.get({"Accent (default)", "Accent"}, kBitwigAccent);
    const Color button = ensureContrast(
        over(theme.get({"Button background", "Abstract Button Unselected Background"},
                       kBitwigButton),
             window),
        window, text, 0.04f);
    const Color pressed =
        over(theme.get({"Pressed button background", "Abstract Button Pressed Background"},
                       kBitwigPressed),
             button);
    const Color menu = over(theme.get({"Menu background", "Tooltip Background"}, button), window);
    const Color stroke =
        theme.get({"Menu stroke", "Tooltip Stroke", "Light Separator Line"}, text.withAlpha(0.2f));
    const Color scrollbar =
        theme.get({"Scrollbar", "Modern Scrollbar Handle (active)"}, text.withAlpha(0.35f));
    const Color separator =
        theme.get({"Light Separator Line", "Panel Stroke (focused)"}, text.withAlpha(0.25f));

    ImVec4* c = style.Colors;
    c[ImGuiCol_Text] = v4(text);
    c[ImGuiCol_TextDisabled] = v4(muted);
    c[ImGuiCol_WindowBg] = v4(window);
    c[ImGuiCol_ChildBg] = v4(window);
    c[ImGuiCol_PopupBg] = v4(menu);
    c[ImGuiCol_Border] = v4(stroke);
    c[ImGuiCol_BorderShadow] = ImVec4(0, 0, 0, 0);
    c[ImGuiCol_FrameBg] = v4(button);
    c[ImGuiCol_FrameBgHovered] = v4(button.mix(text, 0.07f));
    c[ImGuiCol_FrameBgActive] = v4(pressed);
    c[ImGuiCol_Button] = v4(button);
    c[ImGuiCol_ButtonHovered] = v4(button.mix(text, 0.07f));
    c[ImGuiCol_ButtonActive] = v4(pressed);
    c[ImGuiCol_Header] = v4(accent.withAlpha(0.25f));
    c[ImGuiCol_HeaderHovered] = v4(accent.withAlpha(0.35f));
    c[ImGuiCol_HeaderActive] = v4(accent.withAlpha(0.5f));
    c[ImGuiCol_SliderGrab] = v4(accent);
    c[ImGuiCol_SliderGrabActive] = v4(accent.mix(text, 0.2f));
    c[ImGuiCol_CheckMark] = v4(accent);
    c[ImGuiCol_Separator] = v4(separator);
    c[ImGuiCol_SeparatorHovered] = v4(accent.withAlpha(0.6f));
    c[ImGuiCol_SeparatorActive] = v4(accent);
    c[ImGuiCol_ScrollbarBg] = ImVec4(0, 0, 0, 0);
    c[ImGuiCol_ScrollbarGrab] = v4(scrollbar);
    c[ImGuiCol_ScrollbarGrabHovered] = v4(scrollbar.mix(text, 0.2f));
    c[ImGuiCol_ScrollbarGrabActive] = v4(accent);
    c[ImGuiCol_TitleBg] = v4(window);
    c[ImGuiCol_TitleBgActive] = v4(window);
    c[ImGuiCol_MenuBarBg] = v4(window);
    c[ImGuiCol_TextSelectedBg] = v4(accent.withAlpha(0.35f));
    c[ImGuiCol_NavHighlight] = v4(accent);
    c[ImGuiCol_ModalWindowDimBg] =
        v4(theme.get({"Popup overlay background color"}, window.withAlpha(0.6f)));
}

}  // namespace midicomp
