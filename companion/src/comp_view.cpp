#include "comp_view.hpp"

#include <algorithm>
#include <array>
#include <cfloat>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <ctime>
#include <string>
#include <utility>

namespace midicomp {

namespace {

/** Lane identifier passed to ``drawNotes`` for passes being recorded. */
constexpr int kPreviewLane = -2;

std::string str(const int value) {
    return std::to_string(value);
}

std::int64_t unixNow() {
    return std::chrono::duration_cast<std::chrono::seconds>(
               std::chrono::system_clock::now().time_since_epoch())
        .count();
}

/** Formats a Unix time as local "Mon 12, 14:03". */
std::string formatTime(const std::int64_t seconds) {
    const auto t = static_cast<std::time_t>(seconds);
    const std::tm* local = std::localtime(&t);
    if (local == nullptr)
        return "?";
    std::array<char, 32> buffer{};
    std::strftime(buffer.data(), buffer.size(), "%b %d, %H:%M", local);
    return buffer.data();
}

/** Returns a file-name friendly identifier for a new session, e.g. "20261107-140312". */
std::string sessionStamp() {
    const auto t = static_cast<std::time_t>(unixNow());
    const std::tm* local = std::localtime(&t);
    std::array<char, 32> buffer{};
    if (local != nullptr)
        std::strftime(buffer.data(), buffer.size(), "%Y%m%d-%H%M%S", local);
    return buffer.data();
}

/** A comp is worth saving once it holds more than the clip's original notes. */
bool worthSaving(const State& state) {
    return state.takes.size() >= 2 ||
           (state.takes.size() == 1 && state.takes[0].name != "Original");
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
    updateStore(client, state);
    forwardMidi(client, state);
    const bool comping = state != nullptr && state->session.has_value();
    client.setPollPlayhead(comping);
    playhead_ = comping ? client.playhead() : Playhead{};
    playheadStep_ = follower_.position(playhead_, std::chrono::steady_clock::now());
    if (comping)
        handleTransportKeys(client, *state);
    handleScaleKeys();

    drawToolbar(client, state);
    ImGui::Separator();
    if (state != nullptr && state->session)
        drawRestoreOffer(client);
    if (state != nullptr && showLog_)
        drawLog(*state);

    if (state == nullptr) {
        ImGui::TextDisabled("%s", client.connectionDetail().c_str());
        return;
    }
    if (!state->session) {
        laneCount_ = -1;
        drawStart(client, *state);
        return;
    }

    // New lanes (takes, or passes being recorded) appear at the bottom; scroll to them.
    const int lanes = static_cast<int>(state->takes.size() + state->preview().size());
    if (laneCount_ >= 0 && lanes > laneCount_)
        scrollToBottom_ = true;
    laneCount_ = lanes;

    // Arranger clips can only be edited while selected; Bitwig hides them while recording.
    const Session& session = *state->session;
    inputBlocked_ = !session.available && !session.recording;
    const ImVec2 areaPos = ImGui::GetCursorScreenPos();
    const ImVec2 areaSize = ImGui::GetContentRegionAvail();
    ImGui::BeginDisabled(inputBlocked_);
    if (lanes == 0)
        drawRecordHint(*state);
    else
        drawLanes(client, *state);
    ImGui::EndDisabled();
    if (inputBlocked_)
        drawSelectOverlay(*state, areaPos, areaSize);
}

void CompView::drawSelectOverlay(const State& state, const ImVec2 origin, const ImVec2 size) const {
    const Session& session = *state.session;
    // A child window submitted last is drawn over the lane areas.
    ImGui::SetCursorScreenPos(origin);
    ImGui::BeginChild("selectOverlay", size, ImGuiChildFlags_None,
                      ImGuiWindowFlags_NoInputs | ImGuiWindowFlags_NoBackground |
                          ImGuiWindowFlags_NoScrollbar);
    ImDrawList* dl = ImGui::GetWindowDrawList();
    dl->AddRectFilled(origin, ImVec2(origin.x + size.x, origin.y + size.y),
                      withAlpha(palette_.windowBg, 0.72f));

    const float scale = ImGui::GetFontSize() / 13.0f;
    ImFont* font = ImGui::GetFont();
    const float fontSize = ImGui::GetFontSize();
    const std::string track = session.track.empty() ? "comp" : "\"" + session.track + "\"";
    const std::string title = "Click a clip on the " + track + " track in Bitwig";
    const char* reason =
        session.collecting
            ? "The recording is ready. MIDI Comp can read it only from the selected clip."
        : session.pending
            ? "Comp changes are waiting. Bitwig lets extensions edit only the selected arranger "
              "clip."
            : "Bitwig lets extensions edit only the selected arranger clip. Editing resumes once "
              "a clip on that track is selected.";

    const float titleSize = fontSize * 1.5f;
    const ImVec2 titleExtent = font->CalcTextSizeA(titleSize, FLT_MAX, 0.0f, title.c_str());
    const ImVec2 reasonExtent = font->CalcTextSizeA(fontSize, FLT_MAX, 0.0f, reason);
    const float padding = 16.0f * scale;
    const float boxWidth = std::max(titleExtent.x, reasonExtent.x) + 2.0f * padding;
    const float boxHeight = titleExtent.y + reasonExtent.y + 2.0f * padding + 6.0f * scale;
    const ImVec2 boxMin(origin.x + (size.x - boxWidth) * 0.5f,
                        origin.y + std::max(padding, (size.y - boxHeight) * 0.4f));
    const ImVec2 boxMax(boxMin.x + boxWidth, boxMin.y + boxHeight);
    dl->AddRectFilled(boxMin, boxMax, palette_.headerBg, 6.0f * scale);
    dl->AddRect(boxMin, boxMax, palette_.warning, 6.0f * scale, 0, 1.5f * scale);
    dl->AddText(font, titleSize,
                ImVec2(boxMin.x + (boxWidth - titleExtent.x) * 0.5f, boxMin.y + padding),
                palette_.warning, title.c_str());
    dl->AddText(font, fontSize,
                ImVec2(boxMin.x + (boxWidth - reasonExtent.x) * 0.5f,
                       boxMin.y + padding + titleExtent.y + 6.0f * scale),
                palette_.mutedText, reason);
    ImGui::EndChild();
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
    } else {
        const Session& session = *state->session;
        drawTransportButton(client);
        ImGui::SameLine();
        // Buttons whose label changes keep one width, so nothing after them moves.
        const float recordWidth =
            ImGui::CalcTextSize("Record").x + 2.0f * ImGui::GetStyle().FramePadding.x;
        if (session.recording) {
            if (ImGui::Button("Stop", ImVec2(recordWidth, 0)))
                client.send("stopRecording");
            ImGui::SetItemTooltip("%s", session.singlePass
                                            ? "Recording stops at the end of the loop"
                                            : "Stop recording (the current pass becomes a take)");
        } else {
            const bool arranger = session.source == "arranger";
            ImGui::BeginDisabled(!arranger);
            const auto red = ImGui::ColorConvertU32ToFloat4(palette_.error);
            ImGui::PushStyleColor(ImGuiCol_Button, red);
            ImGui::PushStyleColor(ImGuiCol_ButtonHovered,
                                  ImGui::ColorConvertU32ToFloat4(darken(palette_.error, 0.1f)));
            ImGui::PushStyleColor(ImGuiCol_ButtonActive,
                                  ImGui::ColorConvertU32ToFloat4(darken(palette_.error, 0.2f)));
            ImGui::PushStyleColor(ImGuiCol_Text, ImGui::ColorConvertU32ToFloat4(palette_.windowBg));
            if (ImGui::Button("Record", ImVec2(recordWidth, 0)))
                client.send("record", {{"stopAtEnd", stopAtLoopEnd_ ? "true" : "false"}});
            ImGui::PopStyleColor(4);
            ImGui::SetItemTooltip(
                "%s", arranger ? "Record over the arranger loop region into the comp clip"
                               : "For launcher clips, record in Bitwig; each loop "
                                 "pass becomes a take");
            ImGui::EndDisabled();
            ImGui::SameLine();
            ImGui::Checkbox("Stop at loop end", &stopAtLoopEnd_);
            ImGui::SetItemTooltip(
                "On: record one take and stop.\nOff: keep recording; every pass of "
                "the loop becomes a take until you press Stop.");
        }
        ImGui::SameLine();
        ImGui::TextDisabled("|");
        ImGui::SameLine();

        if (ImGui::Button("Add take..."))
            ImGui::OpenPopup("addTake");
        ImGui::SetItemTooltip("Copy the notes of a clip recorded elsewhere as a new take");
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
        if (versionSaved_.exchange(false))
            client.send("newVersion");
        const bool launcherClip = session.source == "launcher";
        ImGui::BeginDisabled(session.recording || versionRequested_ || !launcherClip);
        if (ImGui::Button("New version")) {
            // Save the comp first, so the copy can also be continued by hand.
            versionRequested_ = true;
            client.fetch("export", [this, id = sessionId_,
                                    name = compName_](std::optional<std::string> doc) {
                if (doc && store_ != nullptr && store_->save(id, *doc, unixNow(), name))
                    storeChanged_ = true;
                versionSaved_ = true;
            });
        }
        ImGui::SetItemTooltip(
            "%s", launcherClip
                      ? "Duplicate the clip into another slot and continue comping the copy with "
                        "the same takes and cuts.\nThe original keeps its comp; the copy gets a "
                        "version suffix (\"Lead v2\")."
                      : "Launcher clips only. For an arranger clip, duplicate it to another track "
                        "in Bitwig\nand comp the copy; the companion offers to continue with "
                        "this comp.\n(On the same track, the extension cannot tell the clips "
                        "apart.)");
        ImGui::EndDisabled();
        ImGui::SameLine();
        if (ImGui::Button("Done"))
            client.send("end");
        ImGui::SetItemTooltip("Finish comping; the clip keeps the comp");
    }
    if (state != nullptr && state->session)
        ImGui::SameLine();
    drawMidiPicker();
    ImGui::SameLine();
    drawViewMenu();
    ImGui::SameLine();
    const float logWidth =
        ImGui::CalcTextSize("Hide log").x + 2.0f * ImGui::GetStyle().FramePadding.x;
    if (ImGui::Button(showLog_ ? "Hide log" : "Log", ImVec2(logWidth, 0)))
        showLog_ = !showLog_;
    ImGui::SetItemTooltip("Recent extension events (recording, loop passes)");

    // Status last: its length changes, and nothing after it on this row can move.
    ImGui::SameLine();
    ImGui::TextDisabled("|");
    ImGui::SameLine();
    if (state != nullptr && state->session)
        drawSessionStatus(*state);
    else
        ImGui::TextDisabled("Not comping");

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
        ImGui::SameLine();
        ImGui::TextDisabled("|");
        ImGui::SameLine();
        ImGui::SetNextItemWidth(ImGui::GetFontSize() * 11.0f);
        ImGui::InputTextWithHint("##compName", "Comp name", nameBuffer_.data(), nameBuffer_.size());
        if (ImGui::IsItemDeactivatedAfterEdit()) {
            compName_ = nameBuffer_.data();
            saveNow(client);
        }
        ImGui::SetItemTooltip("Name of this comp in the list of saved comps");
        ImGui::SameLine();
        if (ImGui::Button("Saved comps..."))
            ImGui::OpenPopup("savedComps");
        ImGui::SetItemTooltip("Restore a saved comp of this project");
        drawSavedComps(client, *state);
        ImGui::SameLine();
        ImGui::BeginDisabled(state->session->recording);
        if (ImGui::Button("Load into Bitwig"))
            client.send("loadIntoBitwig");
        ImGui::SetItemTooltip(
            "Write the comp as a MIDI file, with its CC (hi-hat pedal, sustain, pitch bend), and "
            "load it into Bitwig:\ninto the comp clip for launcher clips, into a free launcher "
            "slot of the comp track for arranger clips\n(copy it over the arranger clip from "
            "there). The editor writes notes only; this is how CC gets into the clip.");
        if (state->session->source == "arranger") {
            ImGui::SameLine();
            if (ImGui::Button("Play in launcher"))
                client.send("playInLauncher");
            ImGui::SetItemTooltip(
                "Play the comp from the clip launcher while the other tracks play the arrangement:"
                "\nloads it into a free launcher slot, loops it over the arranger loop region, "
                "continues\nthe session there and launches it at the next loop start. Edits then "
                "go to that clip;\ncopy it over the arranger clip when done. Its automation is "
                "updated with Load into Bitwig.");
        }
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
         4.0f * scale;
    y += centered(y, fontSize, palette_.mutedText,
                  "Arranger clips must stay selected (any clip on their track) for edits to "
                  "apply; launcher clips need not.") +
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
            if (const SavedComp* saved = savedFor(state, *sel)) {
                const std::string note =
                    "Saved comp: " + str(saved->takes) + " takes, " + formatTime(saved->savedAt);
                dl->AddText(ImVec2(a.x + pad, nameY + fontSize * 1.6f), palette_.mutedText,
                            note.c_str());
            }
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

void CompView::drawMidiPicker() {
    const float scale = ImGui::GetFontSize() / 13.0f;
    const float h = ImGui::GetFrameHeight();
    if (midi_ == nullptr) {
        if (demoKeyboard_.empty())
            return;
        const ImVec2 p = ImGui::GetCursorScreenPos();
        ImGui::GetWindowDrawList()->AddCircleFilled(ImVec2(p.x + h * 0.35f, p.y + h * 0.5f),
                                                    h * 0.18f, palette_.mutedText);
        ImGui::Dummy(ImVec2(h * 0.7f, h));
        ImGui::SameLine(0.0f, 0.0f);
        ImGui::SetNextItemWidth(170.0f * scale);
        if (ImGui::BeginCombo("##keyboard", demoKeyboard_.c_str()))
            ImGui::EndCombo();
        return;
    }
    const std::string port = midi_->portName();

    // Activity light: lit briefly by each note.
    const double age = static_cast<double>(monotonicMicros() - midi_->lastNoteMicros()) * 1e-6;
    const bool lit = !port.empty() && midi_->lastNoteMicros() != 0 && age < 0.15;
    if (lit)
        redraw_ = true;
    const ImVec2 p = ImGui::GetCursorScreenPos();
    ImGui::GetWindowDrawList()->AddCircleFilled(
        ImVec2(p.x + h * 0.35f, p.y + h * 0.5f), h * 0.18f,
        lit ? palette_.live : (port.empty() ? palette_.warning : palette_.mutedText));
    ImGui::Dummy(ImVec2(h * 0.7f, h));
    ImGui::SameLine(0.0f, 0.0f);

    const std::string preview = !midi_->available() ? "No MIDI"
                                : port.empty()      ? "No keyboard"
                                                    : portKey(port);
    ImGui::SetNextItemWidth(170.0f * scale);
    if (ImGui::BeginCombo("##keyboard", preview.c_str())) {
        if (ImGui::IsWindowAppearing())
            midiPorts_ = midi_->ports();
        if (ImGui::Selectable("None", port.empty()))
            midi_->close();
        for (const std::string& name : midiPorts_)
            if (ImGui::Selectable(portKey(name).c_str(), name == port))
                midi_->open(name);
        ImGui::EndCombo();
    }
    const std::string error = midi_->error();
    ImGui::SetItemTooltip("Keyboard to record from. MIDI Comp reads the notes you play, so that "
                          "every loop pass becomes a take;\nBitwig keeps only the last pass.%s%s",
                          error.empty() ? "" : "\n\n", error.c_str());
}

void CompView::drawViewMenu() {
    if (ImGui::Button("View"))
        ImGui::OpenPopup("view");
    ImGui::SetItemTooltip("UI scale");
    if (!ImGui::BeginPopup("view"))
        return;
    // The slider keeps its own value while dragged; the scale applies on release, since
    // rebuilding the fonts while dragging would move the slider under the pointer.
    if (!scaleSliderActive_)
        scaleDraft_ = uiScaleRequest_.value_or(uiScale_) * 100.0f;
    ImGui::SetNextItemWidth(160.0f * ImGui::GetFontSize() / 13.0f);
    ImGui::SliderFloat("UI scale", &scaleDraft_, 50.0f, 300.0f, "%.0f%%");
    scaleSliderActive_ = ImGui::IsItemActive();
    if (ImGui::IsItemDeactivatedAfterEdit())
        uiScaleRequest_ = std::clamp(scaleDraft_ / 100.0f, 0.5f, 3.0f);
    ImGui::SameLine();
    if (ImGui::SmallButton("100%"))
        uiScaleRequest_ = 1.0f;
    ImGui::TextDisabled("On top of the display's scale. Ctrl+= / Ctrl+- / Ctrl+0");

    if (settings_ != nullptr) {
        ImGui::Separator();
        bool fit = settings_->laneHeight <= 0.0f;
        if (ImGui::Checkbox("Fit lanes to the window", &fit)) {
            settings_->laneHeight = fit ? 0.0f : 110.0f;
            saveSettings(settingsPath_, *settings_);
        }
        ImGui::BeginDisabled(fit);
        float height = fit ? 110.0f : settings_->laneHeight;
        ImGui::SetNextItemWidth(160.0f * ImGui::GetFontSize() / 13.0f);
        if (ImGui::SliderFloat("Lane height", &height, 40.0f, 400.0f, "%.0f px") && !fit)
            settings_->laneHeight = height;
        if (ImGui::IsItemDeactivatedAfterEdit())
            saveSettings(settingsPath_, *settings_);
        ImGui::EndDisabled();
    }
    ImGui::EndPopup();
}

void CompView::handleScaleKeys() {
    const ImGuiIO& io = ImGui::GetIO();
    if (!io.KeyCtrl || io.WantTextInput)
        return;
    const float current = uiScaleRequest_.value_or(uiScale_);
    if (ImGui::IsKeyPressed(ImGuiKey_Equal, false) ||
        ImGui::IsKeyPressed(ImGuiKey_KeypadAdd, false))
        uiScaleRequest_ = std::min(3.0f, std::round(current * 11.0f) / 10.0f);
    else if (ImGui::IsKeyPressed(ImGuiKey_Minus, false) ||
             ImGui::IsKeyPressed(ImGuiKey_KeypadSubtract, false))
        uiScaleRequest_ = std::max(0.5f, std::round(current * 9.0f) / 10.0f);
    else if (ImGui::IsKeyPressed(ImGuiKey_0, false) || ImGui::IsKeyPressed(ImGuiKey_Keypad0, false))
        uiScaleRequest_ = 1.0f;
}

void CompView::forwardMidi(ApiClient& client, const State* state) {
    if (midi_ == nullptr)
        return;
    midi_->reconnect();
    // Batches of up to a tenth of a second; notes outside comp sessions are not needed.
    const std::int64_t now = monotonicMicros();
    if (now - lastMidiPost_ < 100'000)
        return;
    lastMidiPost_ = now;
    const auto events = midi_->take();
    if (!events.empty() && state != nullptr && state->session)
        client.post("played", playedJson(events, now));
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
                      session.source == "arranger"
                          ? "Set the loop region over the clip and press Record. Each pass "
                            "becomes a take."
                          : "Record into the clip in Bitwig with the loop on. Each pass becomes "
                            "a take.") +
             4.0f * scale;
        y += centered(y, fontSize, palette_.mutedText,
                      "Takes recorded elsewhere can be added with \"Add take\".") +
             4.0f * scale;
        if (midi_ != nullptr && midi_->portName().empty())
            y += centered(y, fontSize, palette_.warning,
                          "No keyboard selected (toolbar): only the last pass can be kept.") +
                 4.0f * scale;
        y += 24.0f * scale;
    }
    y += drawSteps(dl, origin, avail.x, y, 1);
    ImGui::Dummy(ImVec2(avail.x, y));
}

void CompView::drawSessionStatus(const State& state) {
    const Session& s = *state.session;
    const auto color = [](const ImU32 c) { return ImGui::ColorConvertU32ToFloat4(c); };
    // Short writes are not worth showing; only a write that stays pending is.
    const double now = ImGui::GetTime();
    if (!s.pending)
        pendingSince_ = -1.0;
    else if (pendingSince_ < 0.0)
        pendingSince_ = now;

    ImGui::Text("Comping %s", s.label.c_str());
    ImGui::SameLine();
    if (s.recording) {
        ImGui::TextColored(color(palette_.error), "Recording pass %d",
                           std::max(1, s.recordingPass));
    } else if (!s.available) {
        ImGui::TextColored(color(palette_.warning), "%s",
                           s.collecting ? "Recording waiting: select a clip on its track"
                           : s.pending  ? "Changes waiting: select a clip on its track"
                                        : "Select a clip on its track to follow changes");
        ImGui::SetItemTooltip(
            "Bitwig only lets extensions edit the selected arranger clip. Any clip on the comp "
            "clip's track counts,\nincluding one Bitwig creates while recording. Launcher clips "
            "do not have this limit.");
    } else if (s.pending && now - pendingSince_ > 0.5) {
        ImGui::TextColored(color(palette_.warning), "Syncing");
    } else {
        ImGui::TextColored(color(palette_.live), "Live");
    }

    if (s.truncated) {
        ImGui::SameLine();
        ImGui::TextColored(color(palette_.warning), "Clip shorter than comp");
        const auto bars = [&](const int steps) {
            return static_cast<double>(steps) / static_cast<double>(state.barSteps);
        };
        ImGui::SetItemTooltip("The comp is %.2f bars long, the clip %.2f bars.\nNotes after the "
                              "end of the clip are not written; lengthen the clip.",
                              bars(state.length), bars(s.clipLength));
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
    const auto& preview = state.preview();
    const std::size_t laneCount = state.takes.size() + preview.size();
    const float fixedHeight = settings_ != nullptr ? settings_->laneHeight : 0.0f;
    const float takeHeight =
        fixedHeight > 0.0f ? fixedHeight * scale
                           : std::clamp((area.y - topHeight - style.ScrollbarSize) /
                                            static_cast<float>(std::max<std::size_t>(1, laneCount)),
                                        64.0f * scale, 150.0f * scale);

    std::optional<float> applyScroll = std::exchange(requestedScrollX_, std::nullopt);
    redraw_ = false;

    const bool overLanes =
        ImGui::IsWindowHovered(ImGuiHoveredFlags_ChildWindows) &&
        ImGui::IsMouseHoveringRect(areaPos, ImVec2(areaPos.x + area.x, areaPos.y + area.y));
    const float pointerX = io.MousePos.x - areaPos.x;
    if (overLanes && io.KeyCtrl && io.MouseWheel != 0.0f) {
        // Ctrl+wheel zooms around the pointer.
        const float oldPps = pxPerBar_ * scale / static_cast<float>(state.barSteps);
        const float anchorStep = (scrollX_ + pointerX - headerWidth) / oldPps;
        pxPerBar_ = std::clamp(pxPerBar_ * std::pow(1.15f, io.MouseWheel), 20.0f, 2000.0f);
        const float newPps = pxPerBar_ * scale / static_cast<float>(state.barSteps);
        applyScroll = std::max(0.0f, headerWidth + anchorStep * newPps - pointerX);
    } else if (overLanes && io.MousePos.y < areaPos.y + topHeight) {
        // The fixed part has no scrollbar of its own; forward horizontal wheel input.
        float wheelX = io.MouseWheelH;
        if (wheelX == 0.0f && io.KeyShift)
            wheelX = io.MouseWheel;
        if (wheelX != 0.0f)
            applyScroll = std::max(0.0f, scrollX_ - wheelX * 3.0f * ImGui::GetFontSize());
    }

    // The zoom counts unscaled pixels, so a bar takes the same space relative to the text on
    // HiDPI displays.
    const float pps = pxPerBar_ * scale / static_cast<float>(state.barSteps);
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
        drawPlayhead(dl, state, layout, 0.0f, topHeight, true);
        handleRulerInput(client, state, layout, rulerHeight);
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
        ImGui::Dummy(ImVec2(contentWidth, static_cast<float>(laneCount) * takeHeight));
        if (scrollToBottom_) {
            // The cursor is below the content: bring the end into view on the next frame.
            ImGui::SetScrollHereY(1.0f);
            scrollToBottom_ = false;
            redraw_ = true;
        }

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
        // Passes being recorded: provisional, not editable.
        for (const auto& pass : preview) {
            laneRect(dl, layout, y, takeHeight, palette_.laneBg);
            drawGrid(dl, state, layout, y, takeHeight);
            drawNotes(dl, pass.notes, state, layout, y, takeHeight, kPreviewLane);
            dl->AddLine(
                ImVec2(layout.origin.x, layout.origin.y + y + takeHeight - 1.0f),
                ImVec2(layout.origin.x + contentWidth, layout.origin.y + y + takeHeight - 1.0f),
                palette_.laneSeparator);
            y += takeHeight;
        }
        drawPlayhead(dl, state, layout, 0.0f, y, false);
        y = 0.0f;
        for (const auto& take : state.takes) {
            drawTakeHeader(client, state, take, y, takeHeight);
            y += takeHeight;
        }
        for (const auto& pass : preview) {
            drawPreviewHeader(state, pass, y, takeHeight);
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

void CompView::drawTransportButton(ApiClient& client) {
    // Drawn icons: the UI font has no play or stop symbols.
    const float h = ImGui::GetFrameHeight();
    const bool playing = playhead_.transportPlaying;
    if (ImGui::Button("##transport", ImVec2(h * 1.4f, h)))
        client.send("transport", {{"action", playing ? "stop" : "play"}});
    ImGui::SetItemTooltip("%s", playing ? "Stop (Space)" : "Play (Space)");
    const ImVec2 min = ImGui::GetItemRectMin();
    const ImVec2 max = ImGui::GetItemRectMax();
    const ImVec2 c((min.x + max.x) * 0.5f, (min.y + max.y) * 0.5f);
    const float r = h * 0.22f;
    ImDrawList* dl = ImGui::GetWindowDrawList();
    if (playing)
        dl->AddRectFilled(ImVec2(c.x - r, c.y - r), ImVec2(c.x + r, c.y + r), palette_.text);
    else
        dl->AddTriangleFilled(ImVec2(c.x - r * 0.8f, c.y - r * 1.1f),
                              ImVec2(c.x - r * 0.8f, c.y + r * 1.1f), ImVec2(c.x + r * 1.1f, c.y),
                              palette_.text);
}

void CompView::handleTransportKeys(ApiClient& client, const State& state) {
    const ImGuiIO& io = ImGui::GetIO();
    if (io.WantTextInput || io.KeyCtrl || ImGui::IsPopupOpen("", ImGuiPopupFlags_AnyPopupId))
        return;
    if (ImGui::IsKeyPressed(ImGuiKey_Space, false))
        client.send("transport", {{"action", "toggle"}});
    if (!playhead_.canSeek || state.length <= 0)
        return;
    // Left and right move by a grid cell (Shift: a beat), Home to the start of the clip.
    const int grid = io.KeyShift ? state.stepsPerBeat : std::max(1, gridSteps(state));
    const int here = std::max(0, static_cast<int>(std::lround(playheadStep_)));
    std::optional<int> target;
    if (ImGui::IsKeyPressed(ImGuiKey_Home, false))
        target = 0;
    else if (ImGui::IsKeyPressed(ImGuiKey_RightArrow, true))
        target = (here / grid + 1) * grid;
    else if (ImGui::IsKeyPressed(ImGuiKey_LeftArrow, true))
        target = here % grid != 0 ? here / grid * grid : (here / grid - 1) * grid;
    if (target)
        client.send("seek", {{"step", str(std::clamp(*target, 0, state.length))}});
}

void CompView::handleRulerInput(ApiClient& client, const State& state, const Layout& layout,
                                const float height) {
    const float lanesWidth = std::max(1.0f, static_cast<float>(state.length) * layout.pxPerStep);
    ImGui::SetCursorPos(ImVec2(layout.headerWidth, 0.0f));
    ImGui::SetNextItemAllowOverlap();
    ImGui::InvisibleButton("ruler", ImVec2(lanesWidth, height));
    if (!ImGui::IsItemHovered())
        return;
    if (!playhead_.canSeek) {
        ImGui::SetItemTooltip(
            "%s", state.session->source == "launcher"
                      ? "Bitwig does not let extensions move the playhead in launcher clips"
                      : "Play the clip once, then click here to move the playhead");
        return;
    }
    ImGui::SetMouseCursor(ImGuiMouseCursor_Hand);
    ImGui::SetItemTooltip("Click to move the playhead (Shift: off the grid)");
    if (!ImGui::IsItemClicked(ImGuiMouseButton_Left))
        return;
    int step = std::clamp(stepAt(layout, ImGui::GetIO().MousePos.x), 0, state.length);
    if (!ImGui::GetIO().KeyShift)
        if (const auto cell = snapRange(step, step, gridSteps(state), state.length))
            step = cell->first;
    client.send("seek", {{"step", str(step)}});
}

void CompView::drawPlayhead(ImDrawList* dl, const State& state, const Layout& layout, const float y,
                            const float height, const bool marker) const {
    const double step = playheadStep_;
    if (step < 0.0 || step > static_cast<double>(state.length))
        return;
    const float x =
        layout.origin.x + layout.headerWidth + static_cast<float>(step) * layout.pxPerStep;
    if (x < layout.origin.x + layout.visibleLeft || x > layout.origin.x + layout.visibleRight)
        return;
    const float scale = ImGui::GetFontSize() / 13.0f;
    const float top = layout.origin.y + y;
    // A rectangle rather than AddLine, which shifts by half a pixel and would put the line
    // beside the marker's tip.
    const float half = 0.75f * scale;
    dl->AddRectFilled(ImVec2(x - half, top), ImVec2(x + half, top + height), palette_.playhead);
    if (marker) {
        const float w = 5.0f * scale;
        dl->AddTriangleFilled(ImVec2(x - w, top), ImVec2(x + w, top), ImVec2(x, top + w * 1.4f),
                              palette_.playhead);
    }
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
        if (laneTake == kPreviewLane)
            color = withAlpha(palette_.error, 0.3f + 0.4f * static_cast<float>(n.velocity));
        else if (laneTake < 0)
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
    // Ctrl with digits is the UI scale (Ctrl+0), not a take.
    if (inputBlocked_ || !ImGui::IsWindowHovered() || ImGui::GetIO().WantTextInput || drag_ ||
        ImGui::GetIO().KeyCtrl)
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

void CompView::drawPreviewHeader(const State& state, const PreviewPass& pass, const float y,
                                 const float height) const {
    const float scale = ImGui::GetFontSize() / 13.0f;
    const float hx = ImGui::GetWindowPos().x;
    const float top = ImGui::GetWindowPos().y - ImGui::GetScrollY() + y;
    ImDrawList* dl = ImGui::GetWindowDrawList();

    dl->AddRectFilled(ImVec2(hx, top), ImVec2(hx + 190.0f * scale, top + height),
                      palette_.headerBg);
    dl->AddRectFilled(ImVec2(hx, top), ImVec2(hx + 4.0f * scale, top + height),
                      withAlpha(palette_.error, 0.6f));
    const Session& session = *state.session;
    const std::string title = "Pass " + str(pass.pass);
    const char* status = session.recording    ? "recording"
                         : session.collecting ? "waiting for the clip"
                                              : "being added";
    dl->PushClipRect(ImVec2(hx, top), ImVec2(hx + 186.0f * scale, top + height), true);
    dl->AddText(ImVec2(hx + 10.0f * scale, top + 6.0f * scale), palette_.text, title.c_str());
    dl->AddText(ImVec2(hx + 10.0f * scale, top + 22.0f * scale), palette_.mutedText, status);
    dl->AddText(ImVec2(hx + 10.0f * scale, top + 38.0f * scale), palette_.mutedText,
                (str(static_cast<int>(pass.notes.size())) + " notes, from the keyboard").c_str());
    dl->PopClipRect();
}

void CompView::drawTakeHeader(ApiClient& client, const State& state, const Take& take,
                              const float y, const float height) {
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
    ImGui::PushID(take.id);
    if (renamingTake_ == take.id) {
        // Enter keeps the name, Escape or clicking elsewhere without a change cancels.
        ImGui::SetCursorPos(ImVec2(scrollX + 8.0f * scale, y + 3.0f * scale));
        ImGui::SetNextItemWidth(174.0f * scale);
        if (std::exchange(renameFocus_, false))
            ImGui::SetKeyboardFocusHere();
        const bool entered = ImGui::InputText(
            "##takeName", takeNameBuffer_.data(), takeNameBuffer_.size(),
            ImGuiInputTextFlags_EnterReturnsTrue | ImGuiInputTextFlags_AutoSelectAll);
        if (entered || ImGui::IsItemDeactivatedAfterEdit()) {
            if (takeNameBuffer_[0] != '\0' && take.name != takeNameBuffer_.data())
                client.send("renameTake",
                            {{"take", str(take.id)}, {"name", takeNameBuffer_.data()}});
            renamingTake_ = -1;
        } else if (ImGui::IsItemDeactivated() || ImGui::IsKeyPressed(ImGuiKey_Escape)) {
            renamingTake_ = -1;
        }
    } else {
        const std::string title = str(index + 1) + "  " + take.name;
        dl->PushClipRect(ImVec2(hx, top), ImVec2(hx + 186.0f * scale, top + height), true);
        dl->AddText(ImVec2(hx + 10.0f * scale, top + 6.0f * scale), palette_.text, title.c_str());
        dl->PopClipRect();
        ImGui::SetCursorPos(ImVec2(scrollX + 8.0f * scale, y + 4.0f * scale));
        ImGui::InvisibleButton("name", ImVec2(174.0f * scale, ImGui::GetTextLineHeight()));
        ImGui::SetItemTooltip("Double-click to rename");
        if (ImGui::IsItemHovered() && ImGui::IsMouseDoubleClicked(ImGuiMouseButton_Left)) {
            renamingTake_ = take.id;
            renameFocus_ = true;
            takeNameBuffer_.fill('\0');
            take.name.copy(takeNameBuffer_.data(), takeNameBuffer_.size() - 1);
        }
    }
    ImGui::PopID();
    dl->PushClipRect(ImVec2(hx, top), ImVec2(hx + 186.0f * scale, top + height), true);
    dl->AddText(ImVec2(hx + 10.0f * scale, top + 22.0f * scale), palette_.mutedText,
                (take.source + ", " + str(static_cast<int>(take.notes.size())) + " notes" +
                 (take.controls > 0 ? ", " + str(take.controls) + " CC" : ""))
                    .c_str());
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

void CompView::drawLog(const State& state) {
    const float scale = ImGui::GetFontSize() / 13.0f;
    ImGui::SetNextWindowSize(ImVec2(520.0f * scale, 260.0f * scale), ImGuiCond_FirstUseEver);
    if (ImGui::Begin("Extension log", &showLog_)) {
        if (ImGui::SmallButton("Copy")) {
            std::string text;
            for (const auto& line : state.log)
                text += line + "\n";
            ImGui::SetClipboardText(text.c_str());
        }
        ImGui::SetItemTooltip("Copy the log to the clipboard");
        ImGui::Separator();
        if (state.log.empty())
            ImGui::TextDisabled("No events yet. Start comping and record to see what happens.");
        for (const auto& line : state.log)
            ImGui::TextUnformatted(line.c_str());
        // Follow new lines unless the user scrolled up.
        if (ImGui::GetScrollY() >= ImGui::GetScrollMaxY() - 1.0f)
            ImGui::SetScrollHereY(1.0f);
    }
    ImGui::End();
}

void CompView::updateStore(ApiClient& client, const State* state) {
    if (store_ == nullptr)
        return;
    const double now = ImGui::GetTime();
    if (storeChanged_.exchange(false) || now - savedListAt_ > 5.0) {
        saved_ = store_->list();
        savedListAt_ = now;
    }
    if (state == nullptr || !state->session) {
        sessionKey_.clear();
        restoreOffer_.reset();
        return;
    }

    // A new session gets its own file, and is offered the newest earlier comp of the clip.
    const std::string key = state->project + "\n" + state->session->label;
    if (key != sessionKey_) {
        sessionKey_ = key;
        sessionId_ = sessionStamp();
        savedVersion_ = -1;
        lastVersion_ = -1;
        restoreClip_ = state->session->label;
        compName_.clear();
        nameBuffer_.fill('\0');
        restoreOffer_ = versionRequested_.exchange(false)
                            ? std::nullopt
                            : store_->offerFor(state->project, state->session->label,
                                               state->session->fingerprint, sessionId_);
    }

    // Save shortly after the state stops changing.
    if (!worthSaving(*state) || state->version == savedVersion_ || saving_)
        return;
    if (state->version != lastVersion_) {
        lastVersion_ = state->version;
        lastChange_ = now;
        return;
    }
    if (now - lastChange_ < 1.5)
        return;
    saving_ = true;
    const long long version = state->version;
    client.fetch("export", [this, version, id = sessionId_,
                            name = compName_](std::optional<std::string> doc) {
        if (doc && store_->save(id, *doc, unixNow(), name))
            storeChanged_ = true;
        savedVersion_ = version;
        saving_ = false;
    });
}

void CompView::drawRestoreOffer(ApiClient& client) {
    if (!restoreOffer_)
        return;
    const SavedComp offer = *restoreOffer_;
    ImGui::AlignTextToFramePadding();
    const bool sameClip = offer.clip == restoreClip_;
    const auto accent = ImGui::ColorConvertU32ToFloat4(palette_.accent);
    if (sameClip)
        ImGui::TextColored(accent, "Saved comp of this clip%s%s from %s with %d takes.",
                           offer.name.empty() ? "" : ", ", offer.name.c_str(),
                           formatTime(offer.savedAt).c_str(), offer.takes);
    else
        ImGui::TextColored(accent, "This clip holds the comp %s%sof %s (%s, %d takes).",
                           offer.name.c_str(), offer.name.empty() ? "" : " ", offer.clip.c_str(),
                           formatTime(offer.savedAt).c_str(), offer.takes);
    ImGui::SameLine();
    if (ImGui::Button(sameClip ? "Restore" : "Continue with its takes")) {
        restore(client, offer);
        restoreOffer_.reset();
    }
    ImGui::SetItemTooltip("Replace the current takes with the saved comp's takes and cuts");
    ImGui::SameLine();
    if (ImGui::Button("Choose...")) {
        restoreOffer_.reset();
        openSavedComps_ = true;
    }
    ImGui::SetItemTooltip("Pick another saved comp of this project");
    ImGui::SameLine();
    if (ImGui::Button("Dismiss"))
        restoreOffer_.reset();
    ImGui::Separator();
}

void CompView::restore(ApiClient& client, const SavedComp& comp) {
    if (const auto document = store_->load(comp))
        client.send("import", {}, *document);
    compName_ = comp.name;
    nameBuffer_.fill('\0');
    compName_.copy(nameBuffer_.data(), nameBuffer_.size() - 1);
}

void CompView::saveNow(ApiClient& client) {
    if (store_ == nullptr || saving_)
        return;
    saving_ = true;
    client.fetch("export",
                 [this, id = sessionId_, name = compName_](std::optional<std::string> doc) {
                     if (doc && store_->save(id, *doc, unixNow(), name))
                         storeChanged_ = true;
                     saving_ = false;
                 });
}

void CompView::drawSavedComps(ApiClient& client, const State& state) {
    if (store_ == nullptr)
        return;
    if (std::exchange(openSavedComps_, false))
        ImGui::OpenPopup("savedComps");
    const auto& session = *state.session;
    if (ImGui::IsPopupOpen("savedComps") && recordingSets_.empty())
        refreshSavedChoices(state);
    // A list this size belongs in the middle of the window rather than at the pointer.
    const ImGuiViewport* viewport = ImGui::GetMainViewport();
    ImGui::SetNextWindowPos(viewport->GetCenter(), ImGuiCond_Appearing, ImVec2(0.5f, 0.5f));
    if (!ImGui::BeginPopup("savedComps")) {
        recordingSets_.clear();
        return;
    }

    const float scale = ImGui::GetFontSize() / 13.0f;
    if (ImGui::Checkbox("All clips of this project", &showAllClips_))
        refreshSavedChoices(state);
    ImGui::SameLine();
    ImGui::TextDisabled("(otherwise this clip, by title or notes)");

    const SavedComp* chosen = nullptr;
    if (recordingSets_.empty()) {
        ImGui::TextDisabled("No saved comps yet. Comps are saved shortly after every change.");
    } else if (ImGui::BeginTable("savedComps", 2,
                                 ImGuiTableFlags_BordersInnerV | ImGuiTableFlags_SizingFixedFit,
                                 ImVec2(760.0f * scale, 0.0f))) {
        ImGui::TableSetupColumn("Recordings", ImGuiTableColumnFlags_WidthFixed, 330.0f * scale);
        ImGui::TableSetupColumn("Comps", ImGuiTableColumnFlags_WidthStretch);
        // Plain labels: the theme's header color is meant for dark text on light panels.
        ImGui::TableNextRow();
        ImGui::TableSetColumnIndex(0);
        ImGui::TextDisabled("Recordings");
        ImGui::TableSetColumnIndex(1);
        ImGui::TextDisabled("Comps of these recordings");
        ImGui::TableNextRow();
        const float listHeight = 220.0f * scale;
        const float rowHeight = 2.0f * ImGui::GetTextLineHeightWithSpacing();

        // Recordings: the takes, independent of how they are cut.
        ImGui::TableSetColumnIndex(0);
        ImGui::BeginChild("recordings", ImVec2(0.0f, listHeight));
        for (int i = 0; i < static_cast<int>(recordingSets_.size()); ++i) {
            const RecordingSet& set = recordingSets_[static_cast<std::size_t>(i)];
            const std::string title = recordingTitle(set);
            ImGui::PushID(i);
            // Two lines of text drawn over one selectable row.
            const ImVec2 start = ImGui::GetCursorPos();
            if (ImGui::Selectable("##set", i == savedSet_, ImGuiSelectableFlags_AllowOverlap,
                                  ImVec2(0.0f, rowHeight)) &&
                i != savedSet_) {
                savedSet_ = i;
                savedChoice_ = 0;
                syncNameFields();
            }
            const ImVec2 after = ImGui::GetCursorPos();
            ImGui::SetCursorPos(ImVec2(start.x + 4.0f * scale, start.y));
            ImGui::BeginGroup();
            ImGui::TextUnformatted(title.c_str());
            const std::size_t cuts = set.comps.size();
            ImGui::TextDisabled("%s \u00b7 %d takes \u00b7 %zu comp%s", set.newest().clip.c_str(),
                                set.newest().takes, cuts, cuts == 1 ? "" : "s");
            ImGui::EndGroup();
            ImGui::SetCursorPos(after);
            ImGui::PopID();
        }
        ImGui::EndChild();
        ImGui::SetNextItemWidth(-FLT_MIN);
        ImGui::InputTextWithHint("##recordingName", "Name these recordings",
                                 recordingNameBuffer_.data(), recordingNameBuffer_.size());
        if (ImGui::IsItemDeactivatedAfterEdit()) {
            const RecordingSet& set = recordingSets_[static_cast<std::size_t>(savedSet_)];
            store_->setRecordingName(set.takesKey, recordingNameBuffer_.data());
            recordingNames_[set.takesKey] = recordingNameBuffer_.data();
        }

        // Comps: the ways these takes were cut.
        ImGui::TableSetColumnIndex(1);
        ImGui::BeginChild("comps", ImVec2(0.0f, listHeight));
        const auto& comps = recordingSets_[static_cast<std::size_t>(savedSet_)].comps;
        for (int i = 0; i < static_cast<int>(comps.size()); ++i) {
            const SavedComp& comp = comps[static_cast<std::size_t>(i)];
            std::string detail = comp.cutsSummary + " \u00b7 " + formatTime(comp.savedAt);
            if (comp.clip != recordingSets_[static_cast<std::size_t>(savedSet_)].newest().clip)
                detail += " \u00b7 " + comp.clip;
            if (!comp.fingerprint.empty() && comp.fingerprint == session.fingerprint)
                detail += " \u00b7 in this clip";
            ImGui::PushID(i);
            const ImVec2 start = ImGui::GetCursorPos();
            if (ImGui::Selectable("##comp", i == savedChoice_,
                                  ImGuiSelectableFlags_AllowOverlap |
                                      ImGuiSelectableFlags_AllowDoubleClick,
                                  ImVec2(0.0f, rowHeight))) {
                if (i != savedChoice_) {
                    savedChoice_ = i;
                    syncNameFields();
                }
                if (ImGui::IsMouseDoubleClicked(ImGuiMouseButton_Left)) {
                    restore(client, comp);
                    restoreOffer_.reset();
                    ImGui::CloseCurrentPopup();
                }
            }
            const ImVec2 after = ImGui::GetCursorPos();
            ImGui::SetCursorPos(ImVec2(start.x + 4.0f * scale, start.y));
            ImGui::BeginGroup();
            ImGui::TextUnformatted(comp.name.empty() ? "Unnamed comp" : comp.name.c_str());
            ImGui::TextDisabled("%s", detail.c_str());
            ImGui::EndGroup();
            ImGui::SetCursorPos(after);
            ImGui::PopID();
        }
        ImGui::EndChild();
        chosen = &comps[static_cast<std::size_t>(savedChoice_)];
        ImGui::SetNextItemWidth(-ImGui::CalcTextSize("Confirm delete").x -
                                3.0f * ImGui::GetStyle().FramePadding.x -
                                ImGui::GetStyle().ItemSpacing.x);
        ImGui::InputTextWithHint("##savedName", "Name this comp", savedNameBuffer_.data(),
                                 savedNameBuffer_.size());
        if (ImGui::IsItemDeactivatedAfterEdit() && store_->rename(*chosen, savedNameBuffer_.data()))
            refreshAfterChange(state);
        ImGui::SameLine();
        // Two clicks: the first arms the button.
        if (ImGui::Button(deleteArmed_ ? "Confirm delete" : "Delete")) {
            if (deleteArmed_) {
                store_->remove(*chosen);
                deleteArmed_ = false;
                refreshAfterChange(state);
                chosen = nullptr;
            } else {
                deleteArmed_ = true;
            }
        }
        ImGui::SetItemTooltip("Delete this saved comp");
        ImGui::EndTable();
    }

    ImGui::BeginDisabled(chosen == nullptr);
    if (ImGui::Button("Restore comp") && chosen != nullptr) {
        restore(client, *chosen);
        restoreOffer_.reset();
        ImGui::CloseCurrentPopup();
    }
    ImGui::SetItemTooltip("Replace the current takes and cuts with the chosen comp");
    ImGui::SameLine();
    if (ImGui::Button("Restore takes only") && chosen != nullptr) {
        if (const auto document = store_->load(*chosen))
            if (const auto takes = takesOnly(*document))
                client.send("import", {}, *takes);
        compName_.clear();
        nameBuffer_.fill('\0');
        restoreOffer_.reset();
        ImGui::CloseCurrentPopup();
    }
    ImGui::SetItemTooltip("Replace the current takes with these recordings and start a new comp "
                          "(the first take throughout)");
    ImGui::EndDisabled();
    ImGui::SameLine();
    if (ImGui::Button("Close"))
        ImGui::CloseCurrentPopup();
    ImGui::EndPopup();
}

std::string CompView::recordingTitle(const RecordingSet& set) const {
    const auto it = recordingNames_.find(set.takesKey);
    if (it != recordingNames_.end() && !it->second.empty())
        return it->second;
    return "Recorded " + formatTime(set.firstSaved);
}

void CompView::syncNameFields() {
    deleteArmed_ = false;
    recordingNameBuffer_.fill('\0');
    savedNameBuffer_.fill('\0');
    if (recordingSets_.empty())
        return;
    const RecordingSet& set = recordingSets_[static_cast<std::size_t>(savedSet_)];
    if (const auto it = recordingNames_.find(set.takesKey); it != recordingNames_.end())
        it->second.copy(recordingNameBuffer_.data(), recordingNameBuffer_.size() - 1);
    set.comps[static_cast<std::size_t>(savedChoice_)].name.copy(savedNameBuffer_.data(),
                                                                savedNameBuffer_.size() - 1);
}

void CompView::refreshAfterChange(const State& state) {
    // Keep the selection where it was, as far as it still exists.
    const int set = savedSet_;
    const int choice = savedChoice_;
    const std::string takesKey = recordingSets_.empty()
                                     ? std::string{}
                                     : recordingSets_[static_cast<std::size_t>(set)].takesKey;
    refreshSavedChoices(state);
    storeChanged_ = true;
    const auto it = std::ranges::find(recordingSets_, takesKey, &RecordingSet::takesKey);
    if (it != recordingSets_.end()) {
        savedSet_ = static_cast<int>(it - recordingSets_.begin());
        savedChoice_ = std::min(choice, static_cast<int>(it->comps.size()) - 1);
    }
    syncNameFields();
}

void CompView::refreshSavedChoices(const State& state) {
    const auto& session = *state.session;
    const auto best =
        store_->offerFor(state.project, session.label, session.fingerprint, sessionId_);
    std::vector<SavedComp> candidates;
    for (const auto& comp : store_->list()) {
        if (comp.project != state.project || comp.session == sessionId_)
            continue;
        const bool related = comp.clip == session.label ||
                             (!comp.fingerprint.empty() && comp.fingerprint == session.fingerprint);
        if (showAllClips_ || related || (best && comp.takesKey == best->takesKey))
            candidates.push_back(comp);
    }
    recordingSets_ = groupByRecordings(candidates);
    recordingNames_.clear();
    for (const auto& set : recordingSets_)
        recordingNames_[set.takesKey] = store_->recordingName(set.takesKey);
    savedSet_ = 0;
    savedChoice_ = 0;
    // Select the best guess, and move its set to the top.
    if (best) {
        const auto set = std::ranges::find(recordingSets_, best->takesKey, &RecordingSet::takesKey);
        if (set != recordingSets_.end()) {
            std::rotate(recordingSets_.begin(), set, set + 1);
            const auto& comps = recordingSets_.front().comps;
            const auto it = std::ranges::find(comps, best->cutsKey, &SavedComp::cutsKey);
            savedChoice_ = it == comps.end() ? 0 : static_cast<int>(it - comps.begin());
        }
    }
    syncNameFields();
}

const SavedComp* CompView::savedFor(const State& state, const std::string& clip) const {
    for (const auto& comp : saved_) {
        if (comp.project == state.project && comp.clip == clip)
            return &comp;
    }
    return nullptr;
}

}  // namespace midicomp
