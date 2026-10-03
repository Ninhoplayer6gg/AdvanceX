// SPDX-License-Identifier: MPL-2.0
// ax_tool — headless AdvanceX runner for developers, pack authors and CI.
//
//   ax_tool info <rom>
//   ax_tool run <rom> [--frames N] [--hold KEYS] [--screenshot out.ppm]
//                     [--state out.axstate] [--sprites] [--dump-sprites dir]
//                     [--patches blob.axpb] [--advance]
//
// KEYS is a comma list of a,b,select,start,right,left,up,down,r,l.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "ax/common/file_io.h"
#include "ax/common/hash.h"
#include "ax/common/log.h"
#include "ax/core/emulator_core.h"
#include "ax/engine/patch_engine.h"
#include "ax/engine/sprite_detector.h"
#include "ax/runtime/patch_blob.h"
#include "ax/runtime/state_file.h"

using namespace ax;

namespace {

int usage() {
    std::fprintf(stderr,
                 "usage:\n"
                 "  ax_tool info <rom>\n"
                 "  ax_tool run <rom> [--frames N] [--hold KEYS] [--screenshot out.ppm] [--state out.axstate]\n"
                 "                    [--sprites] [--dump-sprites dir] [--patches blob.axpb] [--advance]\n");
    return 2;
}

uint32_t parseKeys(const std::string& list) {
    static const char* names[] = {"a", "b", "select", "start", "right", "left", "up", "down", "r", "l"};
    uint32_t mask = 0;
    size_t start = 0;
    while (start <= list.size()) {
        size_t comma = list.find(',', start);
        std::string token = list.substr(start, comma == std::string::npos ? std::string::npos : comma - start);
        for (int i = 0; i < 10; ++i) {
            if (token == names[i]) mask |= 1u << i;
        }
        if (comma == std::string::npos) break;
        start = comma + 1;
    }
    return mask;
}

bool writePpm(const std::string& path, const uint32_t* pixels, int w, int h, int stride) {
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    std::fprintf(f, "P6 %d %d 255\n", w, h);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            uint32_t p = pixels[y * stride + x];
            unsigned char rgb[3] = {static_cast<unsigned char>(p), static_cast<unsigned char>(p >> 8),
                                    static_cast<unsigned char>(p >> 16)};
            std::fwrite(rgb, 1, 3, f);
        }
    }
    std::fclose(f);
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) return usage();
    log::setMinLevel(log::Level::Warn);
    std::string command = argv[1];
    std::string romPath = argv[2];

    std::vector<uint8_t> rom;
    if (!fileio::readFile(romPath, &rom)) {
        std::fprintf(stderr, "cannot read %s\n", romPath.c_str());
        return 1;
    }
    core::CartridgeHeader header;
    core::parseCartridgeHeader(rom.data(), rom.size(), &header);

    if (command == "info") {
        std::printf("title:    %s\ncode:     %s\nmaker:    %s\nversion:  %u\nchecksum: %s\nsize:     %zu\ncrc32:    %08x\n",
                    header.title.c_str(), header.gameCode.c_str(), header.makerCode.c_str(), header.version,
                    header.checksumValid ? "ok" : "MISMATCH", rom.size(), hash::crc32(rom.data(), rom.size()));
        return 0;
    }
    if (command != "run") return usage();

    int frames = 60;
    uint32_t keys = 0;
    std::string screenshot, statePath, dumpDir, patchPath;
    bool listSprites = false, advance = false;
    for (int i = 3; i < argc; ++i) {
        std::string a = argv[i];
        auto next = [&]() -> std::string { return i + 1 < argc ? argv[++i] : std::string(); };
        if (a == "--frames") frames = std::atoi(next().c_str());
        else if (a == "--hold") keys = parseKeys(next());
        else if (a == "--screenshot") screenshot = next();
        else if (a == "--state") statePath = next();
        else if (a == "--sprites") listSprites = true;
        else if (a == "--dump-sprites") dumpDir = next();
        else if (a == "--patches") patchPath = next();
        else if (a == "--advance") advance = true;
        else return usage();
    }

    uint32_t crc = hash::crc32(rom.data(), rom.size());
    uint32_t size = static_cast<uint32_t>(rom.size());
    engine::PatchEngine patches;
    if (!patchPath.empty() && advance) {
        std::vector<uint8_t> blob;
        runtime::PatchSet set;
        std::string err;
        if (!fileio::readFile(patchPath, &blob) || !runtime::decodePatchBlob(blob.data(), blob.size(), &set, &err)) {
            std::fprintf(stderr, "bad patch blob: %s\n", err.c_str());
            return 1;
        }
        for (const auto& s : engine::applyRomPatches(&rom, set.rom)) {
            std::printf("rom patch %s: state %d %s\n", s.id.c_str(), static_cast<int>(s.state), s.message.c_str());
        }
        patches.setPatches(set.memory);
    }

    auto emu = core::createMgbaCore();
    core::LoadRequest req;
    req.rom = std::move(rom);
    std::string error;
    if (!emu->load(std::move(req), &error)) {
        std::fprintf(stderr, "load failed: %s\n", error.c_str());
        return 1;
    }
    emu->setKeys(keys);
    for (int i = 0; i < frames; ++i) {
        emu->runFrame();
        if (advance) patches.onFrame(emu->memory());
    }
    std::printf("ran %d frames (core frame counter %u)\n", frames, emu->frameCounter());

    if (!screenshot.empty()) {
        auto f = emu->frame();
        if (!writePpm(screenshot, f.pixels, f.width, f.height, f.stridePixels)) return 1;
        std::printf("screenshot: %s\n", screenshot.c_str());
    }
    if (!statePath.empty()) {
        std::vector<uint8_t> state;
        runtime::StateFileInfo info;
        info.coreId = emu->id();
        info.coreVersion = emu->version();
        info.romCrc32 = crc;
        info.romSize = size;
        info.frameCounter = emu->frameCounter();
        if (!emu->saveState(&state) || !runtime::writeStateFile(statePath, state, info, &error)) {
            std::fprintf(stderr, "state failed: %s\n", error.c_str());
            return 1;
        }
        std::printf("state: %s\n", statePath.c_str());
    }
    if (listSprites || !dumpDir.empty()) {
        engine::SpriteDetector detector;
        std::vector<engine::SpriteInstance> sprites;
        detector.scan(emu->memory(), &sprites);
        for (const auto& s : sprites) {
            uint64_t key = engine::replacementKey(s);
            std::printf("sprite oam=%d key=%s pos=(%d,%d) size=%dx%d%s\n", s.oamIndex, engine::hashToHex(key).c_str(),
                        s.x, s.y, s.width, s.height, s.affine ? " affine" : "");
            if (!dumpDir.empty()) {
                std::vector<uint32_t> rgba;
                fileio::makeDirs(dumpDir);
                if (detector.decode(emu->memory(), s, &rgba)) {
                    writePpm(dumpDir + "/" + engine::hashToHex(key) + ".ppm", rgba.data(), s.width, s.height, s.width);
                }
            }
        }
    }
    for (const auto& s : patches.status()) {
        std::printf("memory patch %s: state %d applied %u\n", s.id.c_str(), static_cast<int>(s.state), s.applyCount);
    }
    return 0;
}
