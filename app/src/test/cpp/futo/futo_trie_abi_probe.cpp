// SPDX-License-Identifier: GPL-3.0-only
// Host probe for futo_trie.cpp: loads a real .combined vocabulary with a layout's letter keys and walks each
// probe word's swipe path through the FUTO ITrie vtable, the calls the swipe decoder makes. It prints one JSON line
// per word: whether the path is a word, the word the decoder receives from get_word, the log frequency and, when
// built with -DPROBE_FORMS, the written forms offered in the strip.
// Build: g++ -O2 -std=c++17 -I<dir of futo_trie.cpp> [-DPROBE_FORMS] futo_trie_abi_probe.cpp
// Run:   probe <vocab.combined> <layout letters> WORD...   (words also read from stdin when WORD is "-")
#define FUTO_TRIE_HOST_TEST
#define main futo_trie_self_test_main
#include "futo_trie.cpp"
#undef main

#include <chrono>
#include <iostream>
#include <sys/resource.h>

namespace {

std::vector<int> codepoints(const std::string &text) {
    std::vector<int> out;
    const char *p = text.data();
    const char *end = p + text.size();
    while (p < end) {
        int cp;
        p = decode_utf8(p, end, cp);
        out.push_back(cp);
    }
    return out;
}

std::string json(const std::string &text) {
    std::string out = "\"";
    for (char c : text) {
        if (c == '"' || c == '\\') out.push_back('\\');
        out.push_back(c);
    }
    return out + "\"";
}

// The keys a finger passes for word: letters lowercased, ё on е, ъ on х, everything else skipped.
bool key_path(const std::string &word, const std::vector<int> &letters, std::vector<int> &path) {
    for (int cp : codepoints(word)) {
        int key = lower(cp);
        if (key == 0x0451) key = 0x0435;
        if (key == 0x044A) key = 0x0445;
        const auto found = std::find(letters.begin(), letters.end(), key);
        if (found != letters.end()) path.push_back(static_cast<int>(found - letters.begin()));
        else if (known_letter(key)) return false;
    }
    return !path.empty();
}

}  // namespace

int main(int argc, char **argv) {
    if (argc < 4) {
        std::cerr << "usage: probe <vocab.combined> <layout letters> WORD... ('-' reads words from stdin)\n";
        return 2;
    }
    const auto start = std::chrono::steady_clock::now();
    CombinedTrie *trie = load_trie(argv[1], argv[2]);
    const double load_s = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    if (!trie) {
        std::cerr << "load failed: " << argv[1] << "\n";
        return 1;
    }
    rusage usage{};
    getrusage(RUSAGE_SELF, &usage);
    std::cout << "{\"load_seconds\":" << load_s << ",\"words\":" << trie->size() << ",\"max_rss_kb\":"
              << usage.ru_maxrss << "}\n";

    // The ITrie's letter order is the order of the letters passed in, as FutoSwipeRuntime passes them.
    std::vector<int> letters;
    for (int cp : codepoints(argv[2])) letters.push_back(lower(cp));
    const ITrieVTable *vt = trie->interface()->vtable;
    void *ud = trie->interface()->userdata;
    std::vector<std::string> words;
    for (int i = 3; i < argc; ++i) {
        if (std::string(argv[i]) == "-") {
            std::string line;
            while (std::getline(std::cin, line)) if (!line.empty()) words.push_back(line);
        } else {
            words.push_back(argv[i]);
        }
    }
    for (const std::string &word : words) {
        std::vector<int> path;
        TrieId node = vt->root(ud);
        bool reached = key_path(word, letters, path);
        for (size_t i = 0; reached && i < path.size(); ++i) {
            TrieId next = 0;
            const uint32_t count = vt->get_child_count(ud, node);
            for (uint32_t c = 0; c < count; ++c) {
                const TrieId candidate = vt->get_child(ud, node, c);
                if (vt->get_char_idx(ud, candidate) == path[i]) {
                    next = candidate;
                    break;
                }
            }
            if (!next) reached = false;
            node = next;
        }
        const bool is_word = reached && vt->is_word(ud, node);
        std::cout << "{\"word\":" << json(word) << ",\"is_word\":" << (is_word ? "true" : "false");
        if (is_word) {
            std::cout << ",\"get_word\":" << json(vt->get_word(ud, node))
                      << ",\"log_frequency\":" << vt->get_log_frequency(ud, node);
#ifdef PROBE_FORMS
            std::cout << ",\"forms\":[";
            const auto forms = trie->forms(word);
            for (size_t i = 0; i < forms.size(); ++i) std::cout << (i ? "," : "") << json(forms[i]);
            std::cout << "]";
#endif
        }
        std::cout << "}\n";
        vt->end_search(ud);
    }
    delete trie;
    return 0;
}
