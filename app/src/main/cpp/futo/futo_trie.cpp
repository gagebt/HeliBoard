// SPDX-License-Identifier: GPL-3.0-only
// Raw .combined vocabulary adapter for FUTO Swipe's public ITrie ABI.
// ABI reference: FUTO Swipe commit 1b13f2c85d6b347f6ea3fbc4b3aaf01fce42429a.

#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
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

// A letter without its own key is swiped through the key that holds it: on ЙЦУКЕН, ё is on е and ъ on х.
int key_for_letter(int cp) {
    if (cp == 0x0451) return 0x0435;
    if (cp == 0x044A) return 0x0445;
    return cp;
}

// Marks a swipe skips but a written form keeps, as in don't and из-за.
bool form_mark(int cp) { return cp == '\'' || cp == 0x2019 || cp == '-'; }

constexpr uint8_t kWord = 1;
constexpr uint8_t kPlain = 2;  // the path's own key-letter spelling is a word
constexpr uint8_t kForms = 4;  // written forms of this path are in forms_

struct Node {
    TrieId first_child = 0;
    TrieId next_sibling = 0;
    TrieId parent = 0;
    float frequency = 0;
    uint16_t depth = 0;
    uint8_t parent_char = 0;
    uint8_t flags = 0;
};

constexpr uint32_t kPlainText = UINT32_MAX;

// One written form of a path. text is an offset into the form text pool, or kPlainText for the key-letter spelling.
struct Form {
    TrieId node;
    float frequency;
    uint32_t text;
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

    bool contains(const std::string &word) const { return word_node(word) != 0; }

    // The written forms of the path a word is swiped along, most frequent first; empty when the path has only
    // its key-letter spelling or is no word.
    std::vector<std::string> forms(const std::string &word) {
        std::vector<std::string> out;
        const TrieId node = word_node(word);
        if (!node || !(nodes_[node].flags & kForms)) return out;
        const auto range = form_range(node);
        for (auto form = range.first; form != range.second; ++form) out.push_back(form_text(*form));
        return out;
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
            const bool not_a_word = line.find(",not_a_word=true", comma) != std::string::npos;
            insert(line.substr(word_start, comma - word_start), value, not_a_word);
        }
        finish_forms();
        return words_ != 0;
    }

private:
    // Key indices of the path a finger swipes for surface. Returns false when a letter has no key. exact tells
    // whether surface is spelled with the key letters themselves; writable whether everything else in it is a
    // capital, a letter held by another key, or a form mark, so that it may be offered as the written form.
    bool alpha_chars(const std::string &surface, std::vector<uint8_t> &out, bool *exact = nullptr,
                     bool *writable = nullptr) const {
        bool is_exact = true;
        bool is_writable = true;
        const char *p = surface.data();
        const char *end = p + surface.size();
        while (p < end) {
            int cp;
            p = decode_utf8(p, end, cp);
            const int lowered = lower(cp);
            auto found = codepoint_to_index_.find(lowered);
            if (found == codepoint_to_index_.end()) found = codepoint_to_index_.find(key_for_letter(lowered));
            if (found != codepoint_to_index_.end()) {
                out.push_back(found->second);
                if (cp != codepoints_[found->second]) is_exact = false;
            } else if (known_letter(lowered)) {
                return false;
            } else {
                is_exact = false;
                if (!form_mark(cp)) is_writable = false;
            }
        }
        if (exact) *exact = is_exact;
        if (writable) *writable = is_writable;
        return !out.empty();
    }

    TrieId word_node(const std::string &word) const {
        std::vector<uint8_t> chars;
        if (!alpha_chars(word, chars)) return 0;
        TrieId node = 0;
        for (uint8_t ch : chars) {
            node = child(node, ch);
            if (!node) return 0;
        }
        return (nodes_[node].flags & kWord) ? node : 0;
    }

    void insert(const std::string &surface, float frequency, bool not_a_word) {
        std::vector<uint8_t> chars;
        bool exact = true;
        bool writable = true;
        if (!alpha_chars(surface, chars, &exact, &writable)) return;
        TrieId node = 0;
        for (uint8_t ch : chars) {
            TrieId next = child(node, ch);
            if (!next) {
                const TrieId next = static_cast<TrieId>(nodes_.size());
                const TrieId first_child = nodes_[node].first_child;
                const uint16_t depth = static_cast<uint16_t>(nodes_[node].depth + 1);
                nodes_.push_back(Node{0, first_child, node, 0, depth, ch, 0});
                nodes_[node].first_child = next;
                node = next;
                continue;
            }
            node = next;
        }
        Node &target = nodes_[node];
        if (!(target.flags & kWord)) ++words_;
        target.flags |= kWord;
        const float before = target.frequency;
        target.frequency = std::max(target.frequency, frequency);

        // Entries that are not words, or have no frequency, are never shown as a written form.
        if (not_a_word || frequency <= 0) return;
        if (exact) {
            if (target.flags & kForms) forms_.push_back(Form{node, frequency, kPlainText});
            target.flags |= kPlain;
        } else if (writable) {
            if (!(target.flags & kForms)) {
                target.flags |= kForms;
                // Before the first written form, the node's frequency is the plain spelling's own, unless a form
                // that is never offered (with digits or dots) raised it; the plain spelling then ranks at that value.
                if (target.flags & kPlain) forms_.push_back(Form{node, before, kPlainText});
            }
            forms_.push_back(Form{node, frequency, static_cast<uint32_t>(form_pool_.size())});
            form_pool_.append(surface);
            form_pool_.push_back('\0');
        }
    }

    // Groups forms by node, most frequent first (the plain spelling first on a tie), without repeats.
    void finish_forms() {
        std::stable_sort(forms_.begin(), forms_.end(), [](const Form &a, const Form &b) {
            if (a.node != b.node) return a.node < b.node;
            if (a.frequency != b.frequency) return a.frequency > b.frequency;
            return a.text == kPlainText && b.text != kPlainText;
        });
        std::vector<Form> unique;
        unique.reserve(forms_.size());
        for (const Form &form : forms_) {
            bool repeated = false;
            for (auto it = unique.rbegin(); it != unique.rend() && it->node == form.node; ++it) {
                if (same_text(*it, form)) {
                    repeated = true;
                    break;
                }
            }
            if (!repeated) unique.push_back(form);
        }
        forms_ = std::move(unique);
        forms_.shrink_to_fit();
        form_pool_.shrink_to_fit();
    }

    bool same_text(const Form &a, const Form &b) const {
        if (a.text == kPlainText || b.text == kPlainText) return a.text == b.text;
        return std::strcmp(form_pool_.c_str() + a.text, form_pool_.c_str() + b.text) == 0;
    }

    std::pair<std::vector<Form>::const_iterator, std::vector<Form>::const_iterator> form_range(TrieId id) const {
        return std::equal_range(forms_.begin(), forms_.end(), Form{id, 0, 0},
                                [](const Form &a, const Form &b) { return a.node < b.node; });
    }

    std::string form_text(const Form &form) {
        if (form.text == kPlainText) return reconstruct(form.node);
        return std::string(form_pool_.c_str() + form.text);
    }

    // The word the decoder returns for a node: its most frequent written form.
    const char *written_word(TrieId id) {
        if (id < nodes_.size() && (nodes_[id].flags & kForms)) {
            const auto range = form_range(id);
            if (range.first != range.second && range.first->text != kPlainText) {
                return form_pool_.c_str() + range.first->text;
            }
        }
        return reconstruct(id).c_str();
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
        return id < nodes.size() && (nodes[id].flags & kWord);
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
        return static_cast<CombinedTrie *>(self)->written_word(id);
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
    std::vector<Form> forms_;
    std::string form_pool_;
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
    using Forms = std::vector<std::string>;
    {
        std::ofstream output(path);
        output << "dictionary=main\n word=Hello,f=12\n\tword=can't,f=9\nword=123,f=1\n"
                  "word=its,f=158\nword=it's,f=162\nword=im,f=0,not_a_word=true\nword=I'm,f=116\n"
                  "word=us,f=160\nword=US,f=0\nword=mp3,f=50\n";
    }
    CombinedTrie *english = load_trie(path, "qwertyuiopasdfghjklzxcvbnm");
    assert(english && english->size() == 6 && english->contains("hello") && english->contains("cant"));
    assert(english->forms("cant") == Forms({"can't"}));
    assert(english->forms("hello") == Forms({"Hello"}));
    assert(english->forms("its") == Forms({"it's", "its"}));
    assert(english->forms("im") == Forms({"I'm"}));
    assert(english->forms("us").empty() && english->forms("mp").empty());
    delete english;
    {
        std::ofstream output(path);
        output << "word=Привет,f=8\nword=ёлка,f=7\nword=hello,f=1\nword=все,f=168\nword=всё,f=153\n"
                  "word=объяснить,f=120\nword=из-за,f=157\nword=изза,f=20\n";
    }
    // The shipped ЙЦУКЕН layout has 31 letter keys: ё is on е and ъ on х.
    CombinedTrie *russian = load_trie(path, "йцукенгшщзхфывапролджэячсмитьбю");
    assert(russian && russian->size() == 5 && russian->contains("привет") && russian->contains("ёлка"));
    assert(russian->contains("елка") && russian->forms("ёлка") == Forms({"ёлка"}));
    assert(russian->forms("все") == Forms({"все", "всё"}));
    assert(russian->forms("объяснить") == Forms({"объяснить"}) && russian->contains("обхяснить"));
    assert(russian->forms("изза") == Forms({"из-за", "изза"}));
    delete russian;
    std::remove(path.c_str());
    std::printf("self-test passed\n");
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

extern "C" JNIEXPORT jobjectArray JNICALL
Java_helium314_keyboard_latin_futo_FutoTrie_forms(JNIEnv *env, jobject, jlong handle, jstring word) {
    jclass string_class = env->FindClass("java/lang/String");
    if (!string_class) return nullptr;
    std::vector<std::string> forms;
    if (handle && word) {
        const char *word_chars = env->GetStringUTFChars(word, nullptr);
        if (!word_chars) return nullptr;
        auto *interface = reinterpret_cast<ITrie *>(handle);
        forms = static_cast<CombinedTrie *>(interface->userdata)->forms(word_chars);
        env->ReleaseStringUTFChars(word, word_chars);
    }
    // Forms hold only Basic Multilingual Plane letters, apostrophes and hyphens, so they are valid modified UTF-8.
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(forms.size()), string_class, nullptr);
    if (!out) return nullptr;
    for (size_t i = 0; i < forms.size(); ++i) {
        jstring form = env->NewStringUTF(forms[i].c_str());
        if (!form) return nullptr;
        env->SetObjectArrayElement(out, static_cast<jsize>(i), form);
        env->DeleteLocalRef(form);
    }
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_helium314_keyboard_latin_futo_FutoTrie_close(JNIEnv *, jobject, jlong handle) {
    if (!handle) return;
    auto *interface = reinterpret_cast<ITrie *>(handle);
    delete static_cast<CombinedTrie *>(interface->userdata);
}
#endif
