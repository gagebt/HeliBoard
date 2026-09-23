// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import java.text.Normalizer
import java.util.Locale

internal data class SearchDocument(
    val key: String,
    val title: String,
    val description: String? = null,
    val aliases: List<String> = emptyList(),
    val choices: List<String> = emptyList(),
)

/** Small, local index. Construct once when settings opens; searching reads no files. */
internal class SearchMatcher(documents: List<SearchDocument>) {
    private val conceptCache = HashMap<String, Int>()
    private data class Entry(
        val key: String,
        val title: String,
        val titleWords: Set<String>,
        val choiceWords: Set<String>,
        val aliasWords: Set<String>,
        val descriptionWords: Set<String>,
    )

    private val entries = documents.map { doc ->
        Entry(
            doc.key,
            normalize(doc.title),
            words(doc.title),
            words(doc.choices.joinToString(" ")),
            words(doc.aliases.joinToString(" ")),
            words(doc.description.orEmpty()),
        )
    }

    fun rank(query: String): List<String> {
        val normalized = normalize(query)
        val queryWords = words(normalized)
        if (queryWords.isEmpty()) return emptyList()
        val exact = score(queryWords, normalized, false)
        val strict = exact.filter { it.second == queryWords.size }
        val fuzzy = if (strict.isEmpty() && queryWords.any { it.length >= 5 })
            score(queryWords, normalized, true).filter { it.second == queryWords.size } else emptyList()
        if (queryWords.size == 1) return sorted(if (strict.isNotEmpty()) strict else fuzzy)
        val minimumHits = maxOf(2, (queryWords.size + 1) / 2)
        val matches = (exact.filter { it.second >= minimumHits } + fuzzy).distinctBy { it.first }
        if (matches.isNotEmpty()) return sorted(matches)
        val positiveWords = queryWords - negations
        return if (positiveWords.size == 1 && positiveWords.size < queryWords.size)
            sorted(score(positiveWords, normalized, false).filter { it.second == 1 }) else emptyList()
    }

    private fun score(words: Set<String>, query: String, allowTypo: Boolean): List<Triple<String, Int, Int>> =
        entries.mapNotNull { entry ->
            val hits = words.map { word ->
                maxOf(
                    match(word, entry.titleWords, 12, allowTypo),
                    match(word, entry.choiceWords, 9, allowTypo),
                    match(word, entry.aliasWords, 7, allowTypo),
                    match(word, entry.descriptionWords, 5, allowTypo),
                )
            }
            val count = hits.count { it > 0 }
            if (count == 0) null else {
                val titleBonus = when {
                    entry.title == query -> 1000
                    entry.title.startsWith(query) -> 100
                    else -> 0
                }
                Triple(entry.key, count, hits.sum() + titleBonus)
            }
        }
    private fun sorted(results: List<Triple<String, Int, Int>>): List<String> =
        results.sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }
            .thenByDescending { it.third }).map { it.first }

    private fun match(word: String, candidates: Set<String>, weight: Int, allowTypo: Boolean): Int {
        if (word in candidates) return weight + 2
        if (candidates.any { relatedForms(word, it) }) return weight
        if (candidates.any { sameConcept(word, it) }) return weight - 1
        if (allowTypo && word.length >= 5 && candidates.any { it.length >= 5 && oneEditApart(word, it) })
            return weight / 2
        return 0
    }

    private fun sameConcept(a: String, b: String): Boolean {
        val group = conceptCache.getOrPut(a) { conceptOf(a) }
        return group >= 0 && group == conceptCache.getOrPut(b) { conceptOf(b) }
    }

    private fun oneEditApart(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            if (a.length >= b.length) i++
            if (a.length <= b.length) j++
        }
        return true
    }

    companion object {
        private val mark = Regex("\\p{M}+")
        private val nonWord = Regex("[^\\p{L}\\p{N}]+")
        private val stopWords = setOf(
            "a", "an", "and", "are", "as", "before", "by", "can", "do", "does", "everything",
            "for", "from", "how", "i", "in", "is", "it", "less", "letters", "longer", "make",
            "me", "more", "my", "of", "on", "or", "our", "put", "should", "that", "the",
            "this", "to", "until", "using", "want", "what", "when", "where", "with", "without", "word",
            "words", "would", "you", "your", "please", "some", "off", "after", "another",
            "just", "during", "надо", "буквы", "и", "из", "как", "когда", "меньше", "мне", "мои", "мой", "мою",
            "на", "по", "с", "слова", "со", "текст", "у", "чтобы", "это", "я", "в",
            "во", "для", "от", "до", "за", "при", "или", "а", "же", "бы", "меня", "без",
        )
        private val negations = setOf("not", "no", "never", "не", "никогда")
        private val concepts = listOf(
            listOf("keep", "remember", "save", "store", "retain", "archive", "хран", "сохран", "остав", "запомин"),
            listOf("copy", "copi", "clipboard", "коп", "скоп", "буфер"),
            listOf("speech", "speak", "spoken", "say", "talk", "voice", "dictat", "record", "реч", "говор", "диктов", "голос", "произнес", "запис"),
            listOf("see", "visible", "show", "display", "вид", "показ", "отображ"),
            listOf("wait", "delay", "pause", "gap", "silence", "пауз", "задерж", "ожидан", "тишин"),
            listOf("custom", "unusual", "personal", "сво", "личн", "необыч"),
            listOf("second", "secondary", "another", "втор", "дополн"),
            listOf("type", "typed", "typing", "write", "written", "напис", "введ", "набор", "печата"),
            listOf("auto", "automatic", "авто", "автомат"),
            listOf("tap", "touch", "press", "нажат", "кас"),
            listOf("buzz", "vibrat", "haptic", "вибр", "дрож"),
            listOf("sound", "noise", "click", "звук", "щелч"),
            listOf("correct", "fix", "исправ", "поправ", "коррект"),
            listOf("undo", "revert", "reverse", "отмен", "откат"),
            listOf("key", "keypress", "button", "клавиш", "кноп"),
            listOf("one", "only", "single", "minimal", "миним", "един", "одн"),
        )

        private fun relatedForms(a: String, b: String): Boolean {
            if (minOf(a.length, b.length) >= 3 && (a.startsWith(b) || b.startsWith(a))) return true
            var common = 0
            while (common < minOf(a.length, b.length) && a[common] == b[common]) common++
            val russian = a.firstOrNull() in 'а'..'я' && b.firstOrNull() in 'а'..'я'
            return common >= maxOf(4, minOf(a.length, b.length) - if (russian) 2 else 1)
        }

        private fun conceptOf(word: String): Int = concepts.indexOfFirst { group ->
            group.any { word.startsWith(it) }
        }

        private fun normalize(text: String): String = mark.replace(
            Normalizer.normalize(text.lowercase(Locale.ROOT).replace('ё', 'е'), Normalizer.Form.NFD), ""
        ).replace(nonWord, " ").trim().replace(Regex(" +"), " ")

        private fun words(text: String): Set<String> = normalize(text).split(' ')
            .filter { it.length >= 2 && it !in stopWords }.toSet()
    }
}
