#include "wave.h"
#include <fstream>
#include <iostream>
#include <iterator>
#include <vector>
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    std::ifstream in(argv[1], std::ios::binary);
    if (!in) { std::cerr << "cannot open WAV\n"; return 2; }
    in.seekg(0, std::ios::end);
    const auto size = in.tellg();
    if (size < 0 || size > 16 * 1024 * 1024) return 2;
    in.seekg(0);
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(in)), {});
    try {
        auto w = medialab::parse_wave(bytes.data(), bytes.size());
        std::cout << w.rate << ' ' << w.channels << ' ' << w.offset << ' ' << w.bytes << '\n';
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
