#include "wave.h"
#include <cstring>
#include <stdexcept>
namespace medialab {
static uint16_t u16(const uint8_t* p) { return uint16_t(p[0]) | uint16_t(p[1]) << 8; }
static uint32_t u32(const uint8_t* p) { return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24; }
WaveInfo parse_wave(const uint8_t* p, size_t n) {
    auto reject = [](const char* why) { throw std::invalid_argument(why); };
    if (!p || n < 12) reject("short RIFF header");
    if (std::memcmp(p, "RIFF", 4) || std::memcmp(p + 8, "WAVE", 4)) reject("not RIFF/WAVE");
    if (uint64_t(u32(p + 4)) + 8 != n) reject("RIFF size mismatch");
    WaveInfo result{}; bool fmt = false, data = false;
    for (size_t pos = 12; pos < n;) {
        if (n - pos < 8) reject("short chunk header");
        const size_t len = u32(p + pos + 4), start = pos + 8;
        if (len > n - start || (len & 1) > n - start - len) reject("truncated chunk/padding");
        if (!std::memcmp(p + pos, "fmt ", 4)) {
            if (fmt || len != 16) reject("duplicate/unsupported fmt");
            const uint8_t* f = p + start;
            result.channels = u16(f + 2); result.rate = u32(f + 4);
            if (u16(f) != 1 || u16(f + 14) != 16) reject("only PCM16 supported");
            if (result.channels < 1 || result.channels > 2 || result.rate < 8000 || result.rate > 96000) reject("unsupported channels/rate");
            if (u16(f + 12) != result.channels * 2 || u32(f + 8) != result.rate * result.channels * 2) reject("invalid PCM alignment/rate");
            fmt = true;
        } else if (!std::memcmp(p + pos, "data", 4)) {
            if (data) reject("duplicate data");
            result.offset = start; result.bytes = len; data = true;
        }
        pos = start + len + (len & 1);
    }
    if (!fmt || !data || !result.bytes || result.bytes % (result.channels * 2)) reject("missing/unaligned PCM data");
    return result;
}
}
