#pragma once
#include <cstddef>
#include <cstdint>
namespace medialab {
struct WaveInfo { uint32_t rate; uint16_t channels; size_t offset; size_t bytes; };
// Deliberately bounded subset: little-endian RIFF/WAVE, PCM16 mono/stereo.
WaveInfo parse_wave(const uint8_t* data, size_t size);
}
