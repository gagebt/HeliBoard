// SPDX-License-Identifier: GPL-3.0-only
// Raw .combined vocabulary adapter for FUTO Swipe's public ITrie ABI.
// ABI reference: FUTO Swipe commit 1b13f2c85d6b347f6ea3fbc4b3aaf01fce42429a.

#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#ifndef FUTO_TRIE_HOST_TEST
#include <jni.h>
#endif

using TrieId = uint32_t;

struct ITrieVTable {
    int (*num_chars)(void *);
    TrieId (*root)(void *);
    int (*get_char_idx)(void *, TrieId);
    uint32_t (*get_child_count)(void *, TrieId);
    TrieId (*get_child)(void *, TrieId, uint32_t);
    bool (*is_word)(void *, TrieId);
    float (*get_log_frequency)(void *, TrieId);
    uint16_t (*get_depth)(void *, TrieId);
    const char *(*get_word)(void *, TrieId);
    void (*end_search)(void *);
};

struct ITrie {
    void *userdata;
    const ITrieVTable *vtable;
};

namespace {

const char *decode_utf8(const char *p, const char *end, int &cp) {
    const auto first = static_cast<unsigned char>(*p++);
    if (first < 0x80) {
        cp = first;
        return p;
    }
    int extra;
    if ((first & 0xE0) == 0xC0) {
        cp = first & 0x1F;
        extra = 1;
    } else if ((first & 0xF0) == 0xE0) {
        cp = first & 0x0F;
        extra = 2;
    } else if ((first & 0xF8) == 0xF0) {
        cp = first & 0x07;
        extra = 3;
    } else {
        cp = 0xFFFD;
        return p;
    }
    while (extra-- > 0) {
        if (p == end || (static_cast<unsigned char>(*p) & 0xC0) != 0x80) {
            cp = 0xFFFD;
            return p;
        }
        cp = (cp << 6) | (*p++ & 0x3F);
    }
    return p;
}

void encode_utf8(int cp, std::string &out) {
    if (cp < 0x80) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

int lower(int cp) {
    if (cp >= 'A' && cp <= 'Z') return cp + ('a' - 'A');
    if (cp >= 0x0410 && cp <= 0x042F) return cp + 0x20;
    if (cp == 0x0401) return 0x0451;
    return cp;
}

bool known_letter(int cp) {
    cp = lower(cp);
    if (cp == 0x00B4 || cp == 0x2018 || cp == 0x2019) return false;
    return (cp >= 'a' && cp <= 'z') || (cp > 0x7F && cp < 0xFFFD);
}

struct Node {
    TrieId first_child = 0;
    TrieId next_sibling = 0;
    TrieId parent = 0;
    float frequency = 0;
    uint16_t depth = 0;
    uint8_t parent_char = 0;
    bool word = false;
};

class CombinedTrie {
public:
    explicit CombinedTrie(const std::string &letters) {
        const char *p = letters.data();
        const char *end = p + letters.size();
        while (p < end) {
            int cp;
            p = decode_utf8(p, end, cp);
            cp = lower(cp);
            if (cp == 0xFFFD || codepoint_to_index_.count(cp) || codepoints_.size() == 64) {
                valid_ = false;
                return;
            }
            codepoint_to_index_[cp] = static_cast<uint8_t>(codepoints_.size());
            codepoints_.push_back(cp);
        }
        valid_ = !codepoints_.empty();
        nodes_.emplace_back();
        interface_ = {this, &vtable_};
    }

    bool valid() const { return valid_; }
    size_t size() const { return words_; }
    ITrie *interface() { return &interface_; }

    bool contains(const std::string &word) const {
        std::vector<uint8_t> chars;
        if (!alpha_chars(word, chars)) return false;
        TrieId node = 0;
        for (uint8_t ch : chars) {
            node = child(node, ch);
            if (!node) return false;
        }
        return nodes_[node].word;
    }

    bool load(const std::string &path) {
        std::ifstream input(path);
        if (!input) return false;
        std::string line;
        while (std::getline(input, line)) {
            const size_t start = line.find_first_not_of(" \t");
            if (start == std::string::npos || line.compare(start, 5, "word=") != 0) continue;
            const size_t word_start = start + 5;
            const size_t comma = line.find(',', word_start);
            const size_t freq = line.find(",f=", comma);
            if (comma == std::string::npos || freq == std::string::npos) continue;
            char *after = nullptr;
            const float value = std::strtof(line.c_str() + freq + 3, &after);
            if (after == line.c_str() + freq + 3) continue;
            insert(line.substr(word_start, comma - word_start), value);
        }
        return words_ != 0;
    }

private:
    bool alpha_chars(const std::string &surface, std::vector<uint8_t> &out) const {
        const char *p = surface.data();
        const char *end = p + surface.size();
        while (p < end) {
            int cp;
            p = decode_utf8(p, end, cp);
            cp = lower(cp);
            auto found = codepoint_to_index_.find(cp);
            if (found != codepoint_to_index_.end()) {
                out.push_back(found->second);
            } else if (known_letter(cp)) {
                return false;
            }
        }
        return !out.empty();
    }

    void insert(const std::string &surface, float frequency) {
        std::vector<uint8_t> chars;
        if (!alpha_chars(surface, chars)) return;
        TrieId node = 0;
        for (uint8_t ch : chars) {
            TrieId next = child(node, ch);
            if (!next) {
                const TrieId next = static_cast<TrieId>(nodes_.size());
                const TrieId first_child = nodes_[node].first_child;
                const uint16_t depth = static_cast<uint16_t>(nodes_[node].depth + 1);
                nodes_.push_back(Node{0, first_child, node, 0, depth, ch, false});
                nodes_[node].first_child = next;
                node = next;
                continue;
            }
            node = next;
        }
        if (!nodes_[node].word) ++words_;
        nodes_[node].word = true;
        nodes_[node].frequency = std::max(nodes_[node].frequency, frequency);
    }

    const std::string &reconstruct(TrieId id) {
        scratch_.clear();
        if (id == 0 || id >= nodes_.size()) return scratch_;
        std::vector<uint8_t> chars(nodes_[id].depth);
        for (int i = static_cast<int>(chars.size()) - 1; i >= 0; --i) {
            chars[i] = nodes_[id].parent_char;
            id = nodes_[id].parent;
        }
        for (uint8_t ch : chars) encode_utf8(codepoints_[ch], scratch_);
        return scratch_;
    }

    TrieId child(TrieId id, uint8_t ch) const {
        for (TrieId child = nodes_[id].first_child; child; child = nodes_[child].next_sibling) {
            if (nodes_[child].parent_char == ch) return child;
        }
        return 0;
    }

    static int num_chars(void *self) {
        return static_cast<int>(static_cast<CombinedTrie *>(self)->codepoints_.size());
    }
    static TrieId root(void *) { return 0; }
    static int get_char_idx(void *self, TrieId id) {
        const auto &nodes = static_cast<CombinedTrie *>(self)->nodes_;
        return id < nodes.size() ? nodes[id].parent_char : -1;
    }
    static uint32_t get_child_count(void *self, TrieId id) {
        const auto &nodes = static_cast<CombinedTrie *>(self)->nodes_;
        if (id >= nodes.size()) return 0;
        uint32_t count = 0;
        for (TrieId child = nodes[id].first_child; child; child = nodes[child].next_sibling) ++count;
        return count;
    }
    static TrieId get_child(void *self, TrieId id, uint32_t index) {
        const auto &nodes = static_cast<CombinedTrie *>(self)->nodes_;
        if (id >= nodes.size()) return 0;
        TrieId child = nodes[id].first_child;
        while (child && index-- > 0) child = nodes[child].next_sibling;
        return child;
    }
    static bool is_word(void *self, TrieId id) {
        const auto &nodes = static_cast<CombinedTrie *>(self)->nodes_;
        return id < nodes.size() && nodes[id].word;
    }
    static float get_log_frequency(void *self, TrieId id) {
        const auto &nodes = static_cast<CombinedTrie *>(self)->nodes_;
        return id < nodes.size() ? nodes[id].frequency : 0;
    }
    static uint16_t get_depth(void *self, TrieId id) {
        const auto &nodes = static_cast<CombinedTrie *>(self)->nodes_;
        return id < nodes.size() ? nodes[id].depth : 0;
    }
    static const char *get_word(void *self, TrieId id) {
        return static_cast<CombinedTrie *>(self)->reconstruct(id).c_str();
    }
    static void end_search(void *) {}

    inline static const ITrieVTable vtable_ = {
        num_chars, root, get_char_idx, get_child_count, get_child,
        is_word, get_log_frequency, get_depth, get_word, end_search,
    };

    bool valid_ = false;
    size_t words_ = 0;
    std::vector<int> codepoints_;
    std::unordered_map<int, uint8_t> codepoint_to_index_;
    std::vector<Node> nodes_;
    std::string scratch_;
    ITrie interface_{};
};

CombinedTrie *load_trie(const std::string &path, const std::string &letters) {
    auto *trie = new CombinedTrie(letters);
    if (!trie->valid() || !trie->load(path)) {
        delete trie;
        return nullptr;
    }
    return trie;
}

}  // namespace

#ifdef FUTO_TRIE_HOST_TEST
int main(int argc, char **argv) {
    if (argc == 3) {
        CombinedTrie *trie = load_trie(argv[1], argv[2]);
        if (!trie) return 1;
        std::printf("words=%zu\n", trie->size());
        delete trie;
        return 0;
    }
    const std::string path = "futo-trie-self-test.combined";
    {
        std::ofstream output(path);
        output << "dictionary=main\n word=Hello,f=12\n\tword=can't,f=9\nword=123,f=1\n";
    }
    CombinedTrie *english = load_trie(path, "abcdefghijklmnopqrstuvwxyz");
    assert(english && english->size() == 2 && english->contains("hello") && english->contains("cant"));
    delete english;
    {
        std::ofstream output(path);
        output << "word=Привет,f=8\nword=ёлка,f=7\nword=hello,f=1\n";
    }
    CombinedTrie *russian = load_trie(path, "абвгдеёжзийклмнопрстуфхцчшщъыьэюя");
    assert(russian && russian->size() == 2 && russian->contains("привет") && russian->contains("ёлка"));
    delete russian;
    std::remove(path.c_str());
}
#else
extern "C" JNIEXPORT jlong JNICALL
Java_helium314_keyboard_latin_futo_FutoTrie_load(
    JNIEnv *env, jobject, jstring path, jstring letters) {
    if (!path || !letters) return 0;
    const char *path_chars = env->GetStringUTFChars(path, nullptr);
    const char *letter_chars = env->GetStringUTFChars(letters, nullptr);
    if (!path_chars || !letter_chars) {
        if (path_chars) env->ReleaseStringUTFChars(path, path_chars);
        if (letter_chars) env->ReleaseStringUTFChars(letters, letter_chars);
        return 0;
    }
    CombinedTrie *trie = load_trie(path_chars, letter_chars);
    env->ReleaseStringUTFChars(path, path_chars);
    env->ReleaseStringUTFChars(letters, letter_chars);
    return reinterpret_cast<jlong>(trie ? trie->interface() : nullptr);
}

extern "C" JNIEXPORT void JNICALL
Java_helium314_keyboard_latin_futo_FutoTrie_close(JNIEnv *, jobject, jlong handle) {
    if (!handle) return;
    auto *interface = reinterpret_cast<ITrie *>(handle);
    delete static_cast<CombinedTrie *>(interface->userdata);
}
#endif
