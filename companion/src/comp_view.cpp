#include "comp_view.hpp"

#include <algorithm>
#include <array>
#include <cfloat>
#include <cmath>
#include <string>
#include <utility>

namespace midicomp {

namespace {

std::string str(const int value) {
    return std::to_string(value);
}

bool insideSegmentOf(const State& state, const int take, const int x) {
    return std::ranges::any_of(state.segments, [&](const Segment& s) {
        return s.take == take && x >= s.from && x < s.to;
    });
}

}  // namespace

void CompView::draw(ApiClient& client) {
    const auto statePtr = client.state();
    const State* state = statePtr.get();

    drawToolbar(client, state);
    ImGui::Separator();

    if (state == nullptr) {
        ImGui::TextDisabled("%s", client.connectionDetail().c_str());
        return;
    }
    if (!state->session) {
        drawStart(client, *state);
        return;
    }
    if (state->takes.empty()) {
        drawRecordHint(*state);
        return;
    }
    drawLanes(client, *state);
}

void CompView::drawToolbar(ApiClient& client, const State* state) {
    // Connection indicator.
    const auto connection = client.connection();
    const ImU32 dot = connection == ApiClient::Connection::Connected   ? palette_.live
                      : connection == ApiClient::Connection::Searching ? palette_.mutedText
                                                                       : palette_.error;
    const float h = ImGui::GetFrameHeight();
    const ImVec2 p = ImGui::GetCursorScreenPos();
    ImGui::GetWindowDrawList()->AddCircleFilled(ImVec2(p.x + h * 0.35f, p.y + h * 0.5f), h * 0.22f,
                                                dot);
    ImGui::Dummy(ImVec2(h * 0.7f, h));
    ImGui::SetItemTooltip("%s", client.connectionDetail().c_str());
    ImGui::SameLine();

    const bool haveLauncher = state != nullptr && state->launcherSel.has_value();
    const bool haveArranger = state != nullptr && state->arrangerSel.has_value();

    if (state == nullptr || !state->session) {
        ImGui::TextDisabled("Not comping");
    } else {
        drawSessionStatus(*state);
        ImGui::SameLine();
        ImGui::TextDisabled("|");
        ImGui::SameLine();

        if (ImGui::Button("Add take..."))
            ImGui::OpenPopup("addTake");
        ImGui::SetItemTooltip("Use a clip recorded elsewhere as a take");
        if (ImGui::BeginPopup("addTake")) {
            const std::string launcherItem =
                "Selected launcher clip" + (haveLauncher ? ": " + *state->launcherSel : "");
            if (ImGui::MenuItem(launcherItem.c_str(), nullptr, false, haveLauncher))
                client.send("addTake", {{"source", "launcher"}});
            const std::string arrangerItem =
                "Selected arranger clip" + (haveArranger ? ": " + *state->arrangerSel : "");
            if (ImGui::MenuItem(arrangerItem.c_str(), nullptr, false, haveArranger))
                client.send("addTake", {{"source", "arranger"}});
            ImGui::EndPopup();
        }
        ImGui::SameLine();
        ImGui::BeginDisabled(!state->canRevert);
        if (ImGui::Button("Revert"))
            client.send("revert");
        ImGui::SetItemTooltip("Restore the clip as it was before comping and finish");
        ImGui::EndDisabled();
        ImGui::SameLine();
        if (ImGui::Button("Done"))
            client.send("end");
        ImGui::SetItemTooltip("Finish comping; the clip keeps the comp");
    }

    // Second row: editing controls (only useful while comping) and the last message.
    const bool comping = state != nullptr && state->session.has_value();
    if (comping) {
        ImGui::SetNextItemWidth(ImGui::GetFontSize() * 5.0f);
        const auto current =
            std::ranges::find(kSnapNames, snap_, &std::pair<Snap, std::string_view>::first);
        if (ImGui::BeginCombo("Snap", current->second.data())) {
            for (const auto& [value, name] : kSnapNames) {
                if (ImGui::Selectable(name.data(), value == snap_))
                    snap_ = value;
            }
            ImGui::EndCombo();
        }
        ImGui::SameLine();
        ImGui::SetNextItemWidth(ImGui::GetFontSize() * 10.0f);
        ImGui::SliderFloat("Zoom", &pxPerBar_, 20.0f, 2000.0f, "%.0f px/bar",
                           ImGuiSliderFlags_Logarithmic);
        ImGui::SameLine();
        ImGui::BeginDisabled(!state->canUndoEdit);
        if (ImGui::Button("Undo edit"))
            client.send("undoEdit");
        ImGui::SetItemTooltip("Ctrl+Z");
        ImGui::EndDisabled();
    }

    std::optional<Reply> message = client.lastReply();
    if (!message && state != nullptr && !state->message.empty())
        message = Reply{.ok = true, .message = state->message};
    if (message) {
        if (comping)
            ImGui::SameLine();
        if (message->ok)
            ImGui::TextDisabled("%s", message->message.c_str());
        else
            ImGui::TextColored(ImGui::ColorConvertU32ToFloat4(palette_.error), "%s",
                               message->message.c_str());
    }
    if (client.busy()) {
        ImGui::SameLine();
        ImGui::TextDisabled("...");
    }

    if (state != nullptr && !ImGui::GetIO().WantTextInput &&
        ImGui::IsKeyChordPressed(ImGuiMod_Ctrl | ImGuiKey_Z) && state->canUndoEdit)
        client.send("undoEdit");
}

void CompView::drawStart(ApiClient& client, const State& state) {
    const float scale = ImGui::GetFontSize() / 13.0f;
    const ImVec2 avail = ImGui::GetContentRegionAvail();
    const ImVec2 origin = ImGui::GetCursorScreenPos();
    ImDrawList* dl = ImGui::GetWindowDrawList();
    ImFont* font = ImGui::GetFont();
    const float fontSize = ImGui::GetFontSize();

    const auto textSize = [&](const float size, const char* text) {
        return font->CalcTextSizeA(size, FLT_MAX, 0.0f, text);
    };
    const auto centered = [&](const float y, const float size, const ImU32 color,
                              const char* text) {
        const ImVec2 sz = textSize(size, text);
        dl->AddText(font, size, ImVec2(origin.x + (avail.x - sz.x) * 0.5f, origin.y + y), color,
                    text);
        return sz.y;
    };

    // Heading.
    float y = std::max(24.0f * scale, avail.y * 0.12f);
    y += centered(y, fontSize * 1.6f, palette_.text, "Start comping") + 6.0f * scale;
    y += centered(y, fontSize, palette_.mutedText,
                  "Pick the clip to comp. Loop passes you record into it become takes.") +
         24.0f * scale;

    // One card per place a clip can be selected.
    const float gap = 16.0f * scale;
    const float cardW = std::min(300.0f * scale, (avail.x - 3.0f * gap) * 0.5f);
    const float cardH = 132.0f * scale;
    const float cardsX = (avail.x - (2.0f * cardW + gap)) * 0.5f;
    const float pad = 14.0f * scale;
    const float rounding = 6.0f * scale;

    const auto card = [&](const int index, const char* where, const std::optional<std::string>& sel,
                          const char* hint, const char* source) {
        const float cx = cardsX + static_cast<float>(index) * (cardW + gap);
        const ImVec2 a(origin.x + cx, origin.y + y);
        const ImVec2 b(a.x + cardW, a.y + cardH);
        const bool ready = sel.has_value();

        dl->AddRectFilled(a, b, palette_.compBg, rounding);
        dl->AddRect(a, b, ready ? withAlpha(palette_.accent, 0.8f) : palette_.laneSeparator,
                    rounding, 0, ready ? 1.5f : 1.0f);

        dl->AddText(font, fontSize * 0.85f, ImVec2(a.x + pad, a.y + pad), palette_.mutedText,
                    where);
        const float nameY = a.y + pad + fontSize * 1.2f;
        dl->PushClipRect(ImVec2(a.x + pad, a.y), ImVec2(b.x - pad, b.y), true);
        if (ready) {
            dl->AddText(font, fontSize * 1.25f, ImVec2(a.x + pad, nameY), palette_.text,
                        sel->c_str());
        } else {
            dl->AddText(font, fontSize * 1.25f, ImVec2(a.x + pad, nameY), palette_.mutedText,
                        "Nothing selected");
            dl->AddText(ImVec2(a.x + pad, nameY + fontSize * 1.6f), palette_.mutedText, hint);
        }
        dl->PopClipRect();

        // Primary button in the card's lower right corner.
        const char* label = "Comp this clip";
        const ImVec2 buttonSize(textSize(fontSize, label).x + 24.0f * scale,
                                ImGui::GetFrameHeight() + 4.0f * scale);
        ImGui::SetCursorScreenPos(ImVec2(b.x - pad - buttonSize.x, b.y - pad - buttonSize.y));
        ImGui::PushID(source);
        ImGui::BeginDisabled(!ready);
        ImGui::PushStyleColor(ImGuiCol_Button, ImGui::ColorConvertU32ToFloat4(palette_.accent));
        ImGui::PushStyleColor(ImGuiCol_ButtonHovered,
                              ImGui::ColorConvertU32ToFloat4(darken(palette_.accent, 0.1f)));
        ImGui::PushStyleColor(ImGuiCol_ButtonActive,
                              ImGui::ColorConvertU32ToFloat4(darken(palette_.accent, 0.2f)));
        ImGui::PushStyleColor(ImGuiCol_Text, ImGui::ColorConvertU32ToFloat4(palette_.windowBg));
        if (ImGui::Button(label, buttonSize))
            client.send("start", {{"source", source}});
        ImGui::PopStyleColor(4);
        ImGui::EndDisabled();
        ImGui::PopID();
    };
    card(0, "CLIP LAUNCHER", state.launcherSel, "Select a MIDI clip in the clip launcher",
         "launcher");
    card(1, "ARRANGER", state.arrangerSel, "Select a MIDI clip in the arranger", "arranger");
    y += cardH + 32.0f * scale;

    y += drawSteps(dl, origin, avail.x, y, 0);
    ImGui::SetCursorScreenPos(origin);
    ImGui::Dummy(ImVec2(avail.x, y));
}

float CompView::drawSteps(ImDrawList* dl, const ImVec2 origin, const float width, const float y,
                          const int active) const {
    constexpr std::array<const char*, 3> steps{"Select a clip", "Record with the loop on",
                                               "Drag across takes"};
    ImFont* font = ImGui::GetFont();
    const float fontSize = ImGui::GetFontSize();
    const float scale = fontSize / 13.0f;
    const float radius = fontSize * 0.75f;
    const float labelGap = 8.0f * scale;
    const float stepGap = 36.0f * scale;

    // Measure first so the row as a whole is centered.
    std::array<float, steps.size()> widths{};
    float total = stepGap * static_cast<float>(steps.size() - 1);
    for (std::size_t i = 0; i < steps.size(); ++i) {
        widths[i] =
            2.0f * radius + labelGap + font->CalcTextSizeA(fontSize, FLT_MAX, 0, steps[i]).x;
        total += widths[i];
    }

    float x = origin.x + std::max(0.0f, (width - total) * 0.5f);
    for (std::size_t i = 0; i < steps.size(); ++i) {
        const bool current = static_cast<int>(i) == active;
        const float alpha = current ? 1.0f : 0.45f;
        const ImVec2 center(x + radius, origin.y + y + radius);
        dl->AddCircleFilled(center, radius, palette_.take(static_cast<int>(i) + 1, alpha));
        const std::string number = str(static_cast<int>(i) + 1);
        const ImVec2 numberSize = font->CalcTextSizeA(fontSize, FLT_MAX, 0, number.c_str());
        dl->AddText(ImVec2(center.x - numberSize.x * 0.5f, center.y - numberSize.y * 0.5f),
                    palette_.windowBg, number.c_str());
        dl->AddText(ImVec2(x + 2.0f * radius + labelGap, center.y - fontSize * 0.5f),
                    current ? palette_.text : palette_.mutedText, steps[i]);
        x += widths[i] + stepGap;
    }
    return 2.0f * radius;
}

void CompView::drawRecordHint(const State& state) {
    const Session& session = *state.session;
    const float scale = ImGui::GetFontSize() / 13.0f;
    const ImVec2 avail = ImGui::GetContentRegionAvail();
    const ImVec2 origin = ImGui::GetCursorScreenPos();
    ImDrawList* dl = ImGui::GetWindowDrawList();
    ImFont* font = ImGui::GetFont();
    const float fontSize = ImGui::GetFontSize();

    const auto centered = [&](const float y, const float size, const ImU32 color,
                              const std::string& text) {
        const ImVec2 sz = font->CalcTextSizeA(size, FLT_MAX, 0.0f, text.c_str());
        dl->AddText(font, size, ImVec2(origin.x + (avail.x - sz.x) * 0.5f, origin.y + y), color,
                    text.c_str());
        return sz.y;
    };

    float y = std::max(24.0f * scale, avail.y * 0.2f);
    if (session.recording) {
        y += centered(y, fontSize * 1.6f, palette_.error,
                      "Recording pass " + str(session.passes + 1)) +
             6.0f * scale;
        y += centered(y, fontSize, palette_.mutedText,
                      "Each time the loop starts over, the pass becomes a take.") +
             28.0f * scale;
    } else {
        y += centered(y, fontSize * 1.6f, palette_.text, "Record into " + session.label) +
             6.0f * scale;
        y += centered(y, fontSize, palette_.mutedText,
                      "Set the loop over the clip, arm the track and record. Each pass becomes "
                      "a take.") +
             4.0f * scale;
        y += centered(y, fontSize, palette_.mutedText,
                      "Takes recorded elsewhere can be added with \"Add take\".") +
             28.0f * scale;
    }
    y += drawSteps(dl, origin, avail.x, y, 1);
    ImGui::Dummy(ImVec2(avail.x, y));
}

void CompView::drawSessionStatus(const State& state) {
    const Session& s = *state.session;
    const auto color = [](const ImU32 c) { return ImGui::ColorConvertU32ToFloat4(c); };

    ImGui::Text("Comping %s", s.label.c_str());
    ImGui::SameLine();
    if (s.recording) {
        ImGui::TextColored(color(palette_.error), "Recording pass %d", s.passes + 1);
    } else if (!s.available) {
        ImGui::TextColored(color(palette_.warning), "Select it in the %s to apply changes",
                           s.source == "launcher" ? "clip launcher" : "arranger");
    } else if (s.pending) {
        ImGui::TextColored(color(palette_.warning), "Syncing");
    } else {
        ImGui::TextColored(color(palette_.live), "Live");
    }

    if (!s.overdub) {
        ImGui::SameLine();
        ImGui::TextColored(color(palette_.warning), "Overdub is off");
        ImGui::SetItemTooltip("Without overdub, Bitwig records into a new clip on top of the comp "
                              "clip. Turn %s overdub back on.",
                              s.source == "launcher" ? "launcher" : "arranger");
    }
    if (s.truncated) {
        ImGui::SameLine();
        ImGui::TextColored(color(palette_.warning), "Clip shorter than comp");
        ImGui::SetItemTooltip(
            "Notes after the end of the clip are not written; lengthen the clip.");
    }
}

void CompView::drawLanes(ApiClient& client, const State& state) {
    const float scale = ImGui::GetFontSize() / 13.0f;
    const float headerWidth = 190.0f * scale;
    const float rulerHeight = 22.0f * scale;
    const ImGuiIO& io = ImGui::GetIO();
    const ImGuiStyle& style = ImGui::GetStyle();

    // The ruler and comp lane stay in place; the take lanes below scroll vertically. Both parts
    // share one horizontal scroll position, owned by the take area (it has the scrollbar).
    const ImVec2 areaPos = ImGui::GetCursorScreenPos();
    const float footerHeight =
        ImGui::GetTextLineHeight() + 2.0f * ImGui::GetStyle().ItemSpacing.y + 4.0f * scale;
    const ImVec2 area(ImGui::GetContentRegionAvail().x,
                      ImGui::GetContentRegionAvail().y - footerHeight);
    const float compHeight = std::clamp(area.y * 0.28f, 110.0f * scale, 180.0f * scale);
    const float topHeight = rulerHeight + compHeight;
    const float takeHeight =
        std::clamp((area.y - topHeight - style.ScrollbarSize) /
                       static_cast<float>(std::max<std::size_t>(1, state.takes.size())),
                   64.0f * scale, 150.0f * scale);

    std::optional<float> applyScroll = std::exchange(requestedScrollX_, std::nullopt);
    redraw_ = false;

    const bool overLanes =
        ImGui::IsWindowHovered(ImGuiHoveredFlags_ChildWindows) &&
        ImGui::IsMouseHoveringRect(areaPos, ImVec2(areaPos.x + area.x, areaPos.y + area.y));
    const float pointerX = io.MousePos.x - areaPos.x;
    if (overLanes && io.KeyCtrl && io.MouseWheel != 0.0f) {
        // Ctrl+wheel zooms around the pointer.
        const float oldPps = pxPerBar_ / static_cast<float>(state.barSteps);
        const float anchorStep = (scrollX_ + pointerX - headerWidth) / oldPps;
        pxPerBar_ = std::clamp(pxPerBar_ * std::pow(1.15f, io.MouseWheel), 20.0f, 2000.0f);
        const float newPps = pxPerBar_ / static_cast<float>(state.barSteps);
        applyScroll = std::max(0.0f, headerWidth + anchorStep * newPps - pointerX);
    } else if (overLanes && io.MousePos.y < areaPos.y + topHeight) {
        // The fixed part has no scrollbar of its own; forward horizontal wheel input.
        float wheelX = io.MouseWheelH;
        if (wheelX == 0.0f && io.KeyShift)
            wheelX = io.MouseWheel;
        if (wheelX != 0.0f)
            applyScroll = std::max(0.0f, scrollX_ - wheelX * 3.0f * ImGui::GetFontSize());
    }

    const float pps = pxPerBar_ / static_cast<float>(state.barSteps);
    const float lanesWidth = std::max(1.0f, static_cast<float>(state.length) * pps);
    const float contentWidth = headerWidth + lanesWidth + 40.0f * scale;
    const auto makeLayout = [&] {
        Layout layout;
        layout.headerWidth = headerWidth;
        layout.pxPerStep = pps;
        const float sx = ImGui::GetScrollX();
        const ImVec2 winPos = ImGui::GetWindowPos();
        layout.origin = ImVec2(winPos.x - sx, winPos.y - ImGui::GetScrollY());
        layout.visibleLeft = sx + headerWidth;
        layout.visibleRight = sx + ImGui::GetWindowWidth();
        layout.keys = state.keyRange();
        return layout;
    };
    const auto laneRect = [&](ImDrawList* dl, const Layout& layout, const float y,
                              const float height, const ImU32 color) {
        dl->AddRectFilled(
            ImVec2(layout.origin.x + headerWidth, layout.origin.y + y),
            ImVec2(layout.origin.x + headerWidth + lanesWidth, layout.origin.y + y + height),
            color);
    };

    // Fixed part: ruler and comp lane.
    const float topScroll = applyScroll.value_or(scrollX_);
    ImGui::SetNextWindowScroll(ImVec2(topScroll, 0.0f));
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(0, 0));
    ImGui::BeginChild("comp", ImVec2(area.x - style.ScrollbarSize, topHeight), ImGuiChildFlags_None,
                      ImGuiWindowFlags_NoScrollbar | ImGuiWindowFlags_NoScrollWithMouse);
    {
        const Layout layout = makeLayout();
        ImDrawList* dl = ImGui::GetWindowDrawList();
        ImGui::SetCursorPos(ImVec2(0, 0));
        ImGui::Dummy(ImVec2(contentWidth, topHeight));

        laneRect(dl, layout, 0.0f, rulerHeight, palette_.headerBg);
        drawRuler(dl, state, layout, 0.0f);
        laneRect(dl, layout, rulerHeight, compHeight, palette_.compBg);
        drawGrid(dl, state, layout, rulerHeight, compHeight);
        drawSegments(dl, state, layout, rulerHeight, compHeight, -1);
        drawNotes(dl, state.comp, state, layout, rulerHeight, compHeight, -1);
        handleLaneInput(client, state, layout, -1, rulerHeight, compHeight);

        const float hx = layout.origin.x + layout.visibleLeft - headerWidth;
        const float top = layout.origin.y;
        dl->AddRectFilled(ImVec2(hx, top), ImVec2(hx + headerWidth, top + topHeight),
                          palette_.headerBg);
        dl->AddText(ImVec2(hx + 8.0f * scale, top + rulerHeight + 6.0f * scale), palette_.text,
                    "Comp");
        dl->AddText(ImVec2(hx + 8.0f * scale, top + rulerHeight + 24.0f * scale),
                    palette_.mutedText, "drag here to clear");
        dl->AddLine(ImVec2(hx, top + topHeight - 1.0f),
                    ImVec2(hx + contentWidth, top + topHeight - 1.0f), palette_.laneSeparator);
        handleKeys(client, state, layout);
    }
    ImGui::EndChild();

    // Take lanes, scrolling in both directions.
    if (applyScroll)
        ImGui::SetNextWindowScroll(ImVec2(*applyScroll, -1.0f));
    ImGui::BeginChild("takes", ImVec2(0, area.y - topHeight), ImGuiChildFlags_None,
                      ImGuiWindowFlags_HorizontalScrollbar |
                          ImGuiWindowFlags_AlwaysVerticalScrollbar |
                          (io.KeyCtrl ? ImGuiWindowFlags_NoScrollWithMouse : 0));
    ImGui::PopStyleVar();
    {
        const Layout layout = makeLayout();
        ImDrawList* dl = ImGui::GetWindowDrawList();
        ImGui::SetCursorPos(ImVec2(0, 0));
        ImGui::Dummy(ImVec2(contentWidth, static_cast<float>(state.takes.size()) * takeHeight));

        float y = 0.0f;
        for (const auto& take : state.takes) {
            laneRect(dl, layout, y, takeHeight, palette_.laneBg);
            drawGrid(dl, state, layout, y, takeHeight);
            drawSegments(dl, state, layout, y, takeHeight, take.id);
            drawNotes(dl, take.notes, state, layout, y, takeHeight, take.id);
            handleLaneInput(client, state, layout, take.id, y, takeHeight);
            dl->AddLine(
                ImVec2(layout.origin.x, layout.origin.y + y + takeHeight - 1.0f),
                ImVec2(layout.origin.x + contentWidth, layout.origin.y + y + takeHeight - 1.0f),
                palette_.laneSeparator);
            y += takeHeight;
        }
        y = 0.0f;
        for (const auto& take : state.takes) {
            drawTakeHeader(client, state, take, y, takeHeight);
            y += takeHeight;
        }
        handleKeys(client, state, layout);

        // The fixed part used last frame's position; draw again if the take area moved.
        const float actual = ImGui::GetScrollX();
        if (actual != topScroll)
            redraw_ = true;
        scrollX_ = actual;
    }
    ImGui::EndChild();
    drawHints(state);
}

void CompView::drawHints(const State& state) const {
    // A take number per key only makes sense for the first nine takes.
    const std::string keys =
        state.takes.size() > 1
            ? "1-" + str(static_cast<int>(std::min<std::size_t>(9, state.takes.size())))
            : "1";
    ImGui::TextDisabled(
        "Drag a take: use it there  ·  Drag the comp lane: clear  ·  %s: take under "
        "the pointer  ·  0: clear  ·  Ctrl+wheel: zoom",
        keys.c_str());
}

void CompView::drawRuler(ImDrawList* dl, const State& state, const Layout& layout,
                         const float y) const {
    const float barPx = static_cast<float>(state.barSteps) * layout.pxPerStep;
    const float scale = ImGui::GetFontSize() / 13.0f;
    // Label every n-th bar so that labels keep at least ~36 px apart.
    const int every = std::max(1, static_cast<int>(std::ceil(36.0f * scale / barPx)));
    const int bars = (state.length + state.barSteps - 1) / state.barSteps;
    const int first =
        std::max(0, static_cast<int>((layout.visibleLeft - layout.headerWidth) / barPx) - 1);

    for (int bar = first; bar <= bars; ++bar) {
        const float cx = layout.headerWidth + static_cast<float>(bar) * barPx;
        if (cx > layout.visibleRight)
            break;
        const float sx = layout.origin.x + cx;
        dl->AddLine(ImVec2(sx, layout.origin.y + y + 12.0f * scale),
                    ImVec2(sx, layout.origin.y + y + 22.0f * scale), palette_.barLine);
        if (bar % every == 0 && bar < bars)
            dl->AddText(ImVec2(sx + 3.0f, layout.origin.y + y + 3.0f), palette_.mutedText,
                        str(bar + 1).c_str());
    }
}

void CompView::drawGrid(ImDrawList* dl, const State& state, const Layout& layout, const float y,
                        const float height) const {
    const float top = layout.origin.y + y;
    const float bottom = top + height;
    const float beatPx = static_cast<float>(state.stepsPerBeat) * layout.pxPerStep;
    const float barPx = static_cast<float>(state.barSteps) * layout.pxPerStep;
    const bool drawBeats = beatPx >= 10.0f;
    const float unit = drawBeats ? beatPx : barPx;
    const int units = static_cast<int>(static_cast<float>(state.length) * layout.pxPerStep / unit);
    const int first =
        std::max(0, static_cast<int>((layout.visibleLeft - layout.headerWidth) / unit) - 1);

    for (int i = first; i <= units; ++i) {
        const float cx = layout.headerWidth + static_cast<float>(i) * unit;
        if (cx > layout.visibleRight)
            break;
        const float stepPos = static_cast<float>(i) * unit / layout.pxPerStep;
        const bool isBar = std::fmod(stepPos, static_cast<float>(state.barSteps)) < 0.5f;
        const float sx = layout.origin.x + cx;
        dl->AddLine(ImVec2(sx, top), ImVec2(sx, bottom),
                    isBar ? palette_.barLine : palette_.beatLine);
    }
}

void CompView::drawSegments(ImDrawList* dl, const State& state, const Layout& layout, const float y,
                            const float height, const int laneTake) const {
    const float top = layout.origin.y + y;
    const float scale = ImGui::GetFontSize() / 13.0f;
    for (const auto& seg : state.segments) {
        if (laneTake >= 0 && seg.take != laneTake)
            continue;
        const float x0 =
            layout.origin.x + layout.headerWidth + static_cast<float>(seg.from) * layout.pxPerStep;
        const float x1 =
            layout.origin.x + layout.headerWidth + static_cast<float>(seg.to) * layout.pxPerStep;
        dl->AddRectFilled(ImVec2(x0, top), ImVec2(x1, top + height),
                          palette_.take(seg.take, 0.13f));
        dl->AddRectFilled(ImVec2(x0, top + height - 3.0f * scale), ImVec2(x1, top + height),
                          palette_.take(seg.take, 0.9f));
        if (laneTake < 0 && x1 - x0 > 28.0f * scale) {
            const int index = state.takeIndex(seg.take);
            const std::string label = "T" + str(index + 1);
            dl->AddText(ImVec2(x0 + 4.0f, top + height - 18.0f * scale),
                        palette_.take(seg.take, 0.9f), label.c_str());
        }
    }
}

void CompView::drawNotes(ImDrawList* dl, const std::vector<Note>& notes, const State& state,
                         const Layout& layout, const float y, const float height,
                         const int laneTake) const {
    const auto [low, high] = layout.keys;
    const float inner = height - 8.0f;
    const float keyHeight = inner / static_cast<float>(high - low + 1);
    const float noteHeight = std::max(2.5f, keyHeight - 1.0f);
    const float top = layout.origin.y + y + 4.0f;
    const float firstVisibleStep = (layout.visibleLeft - layout.headerWidth) / layout.pxPerStep;
    const float lastVisibleStep = (layout.visibleRight - layout.headerWidth) / layout.pxPerStep;

    for (const auto& n : notes) {
        const float start = static_cast<float>(n.x);
        const float end = start + static_cast<float>(n.duration);
        if (end < firstVisibleStep || start > lastVisibleStep)
            continue;
        const float x0 = layout.origin.x + layout.headerWidth + start * layout.pxPerStep;
        const float x1 =
            std::max(x0 + 2.0f, layout.origin.x + layout.headerWidth + end * layout.pxPerStep);
        const float ny = top + static_cast<float>(high - std::clamp(n.key, low, high)) * keyHeight;

        ImU32 color = 0;
        const float alpha = 0.45f + 0.55f * static_cast<float>(n.velocity);
        if (laneTake < 0)
            color = palette_.take(n.take, alpha);
        else if (insideSegmentOf(state, laneTake, n.x))
            color = palette_.take(laneTake, alpha);
        else
            color =
                withAlpha(palette_.inactiveNote, 0.25f + 0.35f * static_cast<float>(n.velocity));

        const ImVec2 a(x0, ny);
        const ImVec2 b(x1, ny + noteHeight);
        const float rounding = noteHeight >= 4.0f ? 1.5f : 0.0f;
        if (n.muted) {
            dl->AddRect(a, b, color, rounding);
        } else {
            dl->AddRectFilled(a, b, color, rounding);
            if (noteHeight >= 4.0f)
                dl->AddRect(a, b, darken(color, 0.35f), rounding);
        }
    }
}

void CompView::handleLaneInput(ApiClient& client, const State& state, const Layout& layout,
                               const int lane, const float y, const float height) {
    const float lanesWidth = std::max(1.0f, static_cast<float>(state.length) * layout.pxPerStep);
    ImGui::SetCursorPos(ImVec2(layout.headerWidth, y));
    ImGui::PushID(lane);
    ImGui::SetNextItemAllowOverlap();
    ImGui::InvisibleButton("lane", ImVec2(lanesWidth, height));
    ImGui::PopID();

    const ImGuiIO& io = ImGui::GetIO();
    const int pointerStep = stepAt(layout, io.MousePos.x);
    const float top = layout.origin.y + y;
    const auto highlight = [&](const std::pair<int, int>& range, const ImU32 fill,
                               const ImU32 border) {
        const float x0 = layout.origin.x + layout.headerWidth +
                         static_cast<float>(range.first) * layout.pxPerStep;
        const float x1 = layout.origin.x + layout.headerWidth +
                         static_cast<float>(range.second) * layout.pxPerStep;
        ImDrawList* dl = ImGui::GetWindowDrawList();
        dl->AddRectFilled(ImVec2(x0, top), ImVec2(x1, top + height), fill);
        dl->AddRect(ImVec2(x0, top), ImVec2(x1, top + height), border);
    };
    const ImU32 fill = lane < 0 ? palette_.clearFill : palette_.selectionFill;
    const ImU32 stroke = lane < 0 ? palette_.clearStroke : palette_.selectionStroke;

    if (ImGui::IsItemActivated())
        drag_ = Drag{.lane = lane, .startStep = pointerStep, .endStep = pointerStep};

    if (drag_ && drag_->lane == lane) {
        if (ImGui::IsItemActive()) {
            drag_->endStep = pointerStep;
            if (const auto range =
                    snapRange(drag_->startStep, drag_->endStep, gridSteps(state), state.length))
                highlight(*range, fill, stroke);

            // Scroll while dragging near the edges; applied to both lane areas next frame.
            const float winX = ImGui::GetWindowPos().x;
            const float edge = 30.0f;
            if (io.MousePos.x > winX + ImGui::GetWindowWidth() - edge)
                requestedScrollX_ = scrollX_ + 12.0f;
            else if (io.MousePos.x < winX + layout.headerWidth + edge)
                requestedScrollX_ = std::max(0.0f, scrollX_ - 12.0f);
        }
        if (ImGui::IsItemDeactivated()) {
            commitRange(client, state, lane, drag_->startStep, drag_->endStep);
            drag_.reset();
        }
    } else if (!drag_ && ImGui::IsItemHovered()) {
        if (const auto cell = snapRange(pointerStep, pointerStep, gridSteps(state), state.length))
            highlight(*cell, withAlpha(stroke, 0.06f), withAlpha(stroke, 0.35f));
    }
}

void CompView::handleKeys(ApiClient& client, const State& state, const Layout& layout) {
    if (!ImGui::IsWindowHovered() || ImGui::GetIO().WantTextInput || drag_)
        return;
    const int step = stepAt(layout, ImGui::GetIO().MousePos.x);
    const auto cell = snapRange(step, step, gridSteps(state), state.length);
    if (!cell)
        return;

    for (int i = 0; i < 9; ++i) {
        if (!ImGui::IsKeyPressed(static_cast<ImGuiKey>(ImGuiKey_1 + i), false))
            continue;
        if (static_cast<std::size_t>(i) < state.takes.size())
            commitRange(client, state, state.takes[static_cast<std::size_t>(i)].id, cell->first,
                        cell->second - 1);
    }
    if (ImGui::IsKeyPressed(ImGuiKey_0, false) || ImGui::IsKeyPressed(ImGuiKey_Delete, false))
        commitRange(client, state, -1, cell->first, cell->second - 1);
}

void CompView::drawTakeHeader(ApiClient& client, const State& state, const Take& take,
                              const float y, const float height) const {
    const float scale = ImGui::GetFontSize() / 13.0f;
    const float scrollX = ImGui::GetScrollX();
    const ImVec2 origin(ImGui::GetWindowPos().x - scrollX,
                        ImGui::GetWindowPos().y - ImGui::GetScrollY());
    const float hx = origin.x + scrollX;
    const float top = origin.y + y;
    ImDrawList* dl = ImGui::GetWindowDrawList();

    dl->AddRectFilled(ImVec2(hx, top), ImVec2(hx + 190.0f * scale, top + height),
                      palette_.headerBg);
    dl->AddRectFilled(ImVec2(hx, top), ImVec2(hx + 4.0f * scale, top + height),
                      palette_.take(take.id));

    const int index = state.takeIndex(take.id);
    const std::string title = str(index + 1) + "  " + take.name;
    dl->PushClipRect(ImVec2(hx, top), ImVec2(hx + 186.0f * scale, top + height), true);
    dl->AddText(ImVec2(hx + 10.0f * scale, top + 6.0f * scale), palette_.text, title.c_str());
    dl->AddText(ImVec2(hx + 10.0f * scale, top + 22.0f * scale), palette_.mutedText,
                (take.source + ", " + str(static_cast<int>(take.notes.size())) + " notes").c_str());
    dl->PopClipRect();

    ImGui::SetCursorPos(
        ImVec2(scrollX + 10.0f * scale, y + height - ImGui::GetFrameHeight() - 6.0f * scale));
    ImGui::PushID(take.id);
    if (ImGui::SmallButton("Use all"))
        client.send("useTake", {{"take", str(take.id)}});
    ImGui::SetItemTooltip("Use this take for the whole comp (key %d)", index + 1);
    ImGui::SameLine();
    if (ImGui::SmallButton("Remove"))
        client.send("removeTake", {{"take", str(take.id)}});
    ImGui::PopID();
}

int CompView::stepAt(const Layout& layout, const float screenX) const {
    const float step = (screenX - layout.origin.x - layout.headerWidth) / layout.pxPerStep;
    return static_cast<int>(std::floor(std::max(0.0f, step)));
}

int CompView::gridSteps(const State& state) const {
    return snapSteps(snap_, state.stepsPerBeat, state.barSteps);
}

void CompView::commitRange(ApiClient& client, const State& state, const int lane, const int a,
                           const int b) const {
    const auto range = snapRange(a, b, gridSteps(state), state.length);
    if (!range)
        return;
    if (lane < 0)
        client.send("clear", {{"from", str(range->first)}, {"to", str(range->second)}});
    else
        client.send("assign",
                    {{"take", str(lane)}, {"from", str(range->first)}, {"to", str(range->second)}});
}

}  // namespace midicomp
