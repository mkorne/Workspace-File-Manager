package com.example.workspacefilemanager.study

import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * Local (extractive) question engine — the offline fallback for the study tab.
 *
 * Everything is generated from the opened file's text on the device. The engine
 * never invents facts: each question is built from one real sentence, and every
 * explanation quotes that sentence.
 *
 * Design rules that the previous version broke:
 *
 *  • A sentence must actually end with `.`, `!` or `?` to be used — the old
 *    filter required a literal ".?" / ".!" ending, so it rejected every normal
 *    sentence and produced an empty quiz.
 *  • A question prompt is the sentence with a keyword blanked out. The old
 *    prompts were the literal string "_BLANK_".
 *  • Multiple-choice options are the correct keyword plus three distinct words
 *    taken from *other* sentences, then shuffled with the index of the answer
 *    recorded. The old version built distractors out of the answer's own words,
 *    so options repeated and the quiz was trivially guessable.
 *  • True/False questions are only labelled "true" when the statement is the
 *    original sentence; a "false" question is the sentence with one keyword
 *    swapped for a distractor, so the answer really is false.
 *  • Short answers are built only from definition-style sentences ("X is Y"),
 *    and are skipped when no such sentence exists instead of emitting a
 *    meaningless placeholder.
 */
object StudyEngine {

    /** Question types used when the caller does not specify a rotation. */
    val ALL_TYPES: List<QuestionType> = listOf(
        QuestionType.MultipleChoice,
        QuestionType.TrueFalse,
        QuestionType.Cloze,
        QuestionType.ShortAnswer
    )

    /** Marker written into a prompt where the answer was removed. */
    const val BLANK: String = "_____"

    private const val MIN_SENTENCE = 40
    private const val MAX_SENTENCE = 400
    private const val MIN_WORDS = 6
    private const val MIN_KEYWORD = 4
    private const val MAX_SUBJECT_WORDS = 6

    // ── Sentence segmentation ─────────────────────────────────────────────────

    /**
     * Words whose trailing period does not end a sentence. Without this,
     * "Dr. Smith ..." is split after "Dr." and both halves are unusable.
     */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc",
        "eg", "ie", "fig", "figs", "eq", "eqs", "vol", "vols", "pp", "al",
        "approx", "dept", "est", "inc", "ltd", "corp", "no", "nos", "cf", "resp"
    )

    /** Split after `.`/`!`/`?` (plus closing quotes/brackets) and whitespace. */
    private val SENTENCE_BREAK = Regex("""(?<=[.!?])["'”’)\]]*\s+""")

    /**
     * Split prose into sentences.
     *
     * Fragments that end on a known abbreviation, a single initial or a bare
     * number are merged into the next fragment rather than treated as complete.
     */
    fun splitIntoSentences(text: String): List<String> {
        val normalized = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\t', ' ')
            .replace(Regex(""" {2,}"""), " ")

        val fragments = SENTENCE_BREAK.split(normalized)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val sentences = mutableListOf<String>()
        val buffer = StringBuilder()
        for (fragment in fragments) {
            if (buffer.isNotEmpty()) buffer.append(' ').append(fragment)
            else buffer.append(fragment)
            if (endsSentence(buffer.toString())) {
                sentences += buffer.toString().trim()
                buffer.clear()
            }
        }
        if (buffer.isNotBlank()) sentences += buffer.toString().trim()
        return sentences
    }

    private fun endsSentence(text: String): Boolean {
        val trimmed = text.trimEnd('"', '\'', '”', '’', ')', ']')
        if (trimmed.isEmpty()) return false
        val last = trimmed.last()
        if (last != '.' && last != '!' && last != '?') return false
        if (last != '.') return true

        // A period only ends a sentence when it is not part of an abbreviation,
        // an initial ("J.") or a trailing number ("3.").
        val lastWord = trimmed.dropLast(1)
            .takeLastWhile { it.isLetter() || it == '.' }
            .lowercase(Locale.ROOT)
        if (lastWord.isEmpty()) return false
        if (lastWord.length == 1) return false
        return lastWord !in ABBREVIATIONS
    }

    // ── Content filtering ────────────────────────────────────────────────────

    private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    private val CODE = Regex("""[{}<>]|=>|::|\w\)\s*;|\b(?:fun|def|class|var|val|import)\s+\w+\s*[(:]""")
    private val BOILERPLATE = Regex(
        """(?i)\b(?:table of contents|copyright|all rights reserved|isbn|doi|figure|fig\.|see appendix|downloaded from|page \d+)\b"""
    )
    private val LIST_OR_HEADING = Regex("""^(?:#{1,6}\s|[-*+•]\s|\d+[.)]\s|\|)""")
    private val WORD = Regex("""[A-Za-z][A-Za-z'’-]*""")

    private val STOPWORDS = setOf(
        "about", "above", "across", "after", "again", "against", "almost", "along",
        "also", "although", "always", "among", "another", "anyone", "anything",
        "around", "because", "before", "behind", "being", "below", "beside", "besides",
        "between", "both", "cannot", "could", "does", "doing", "done", "down",
        "during", "each", "either", "else", "enough", "even", "ever", "every",
        "everyone", "everything", "except", "from", "further", "have", "having",
        "here", "herself", "himself", "however", "into", "itself", "just", "least",
        "less", "like", "made", "make", "many", "might", "more", "most", "much",
        "must", "myself", "near", "need", "neither", "never", "next", "none",
        "nothing", "often", "once", "only", "other", "others", "otherwise", "ought",
        "over", "own", "perhaps", "quite", "rather", "really", "said", "same",
        "shall", "should", "since", "some", "someone", "something", "still", "such",
        "than", "that", "their", "theirs", "them", "themselves", "then", "there",
        "therefore", "these", "they", "thing", "things", "this", "those", "though",
        "through", "thus", "together", "under", "until", "upon", "very", "want",
        "well", "were", "what", "whatever", "when", "where", "whether", "which",
        "while", "whom", "whose", "will", "with", "within", "without", "would",
        "your", "yours", "yourself", "using", "used", "uses", "also", "been"
    )

    /**
     * True when [sentence] is worth turning into a question.
     *
     * Rejects headings, list items, URLs, code, boilerplate/front-matter,
     * digit-heavy text, shouting and sentences without a usable keyword.
     */
    fun isContentSentence(sentence: String): Boolean {
        val text = sentence.trim()
        if (text.length !in MIN_SENTENCE..MAX_SENTENCE) return false
        if (LIST_OR_HEADING.containsMatchIn(text)) return false
        if (URL.containsMatchIn(text)) return false
        if (CODE.containsMatchIn(text)) return false
        if (BOILERPLATE.containsMatchIn(text)) return false
        if (text.count { it.isDigit() } > text.length / 3) return false
        if (text.uppercase(Locale.ROOT) == text && text.length > 20) return false
        if (WORD.findAll(text).count() < MIN_WORDS) return false
        return rankKeywords(text).isNotEmpty()
    }

    // ── Keyword extraction ───────────────────────────────────────────────────

    /**
     * A candidate answer word, with its position in the source sentence.
     *
     * A word capitalised only because it starts the sentence is lowercased when
     * used as an option, otherwise the capital letter gives the answer away.
     */
    private data class Keyword(
        val display: String,
        val lower: String,
        val index: Int,
        val score: Int,
        val sentenceInitial: Boolean
    ) {
        val option: String
            get() = if (sentenceInitial) {
                display.replaceFirstChar { it.lowercaseChar() }
            } else {
                display
            }
    }

    /**
     * Rank the testable words in [sentence], best first.
     *
     * Longer, non-stopword words score highest; proper nouns get a bonus
     * because they are unambiguous answers. Hyphenated compounds and
     * possessives are penalised because blanking them reads badly.
     */
    private fun rankKeywords(sentence: String): List<Keyword> {
        val matches = WORD.findAll(sentence).toList()
        if (matches.isEmpty()) return emptyList()

        val seen = mutableSetOf<String>()
        val ranked = mutableListOf<Keyword>()

        // A word that appears twice cannot be blanked unambiguously: the prompt
        // would still show the answer, so such words are never used.
        val frequency = matches.groupingBy {
            it.value.trim('’', '\'', '-').lowercase(Locale.ROOT)
        }.eachCount()

        matches.forEachIndexed { position, match ->
            val raw = match.value.trim('’', '\'', '-')
            if (raw.length < MIN_KEYWORD) return@forEachIndexed
            if (raw.all { it.isDigit() }) return@forEachIndexed

            val lower = raw.lowercase(Locale.ROOT)
            if (lower in STOPWORDS) return@forEachIndexed
            if ((frequency[lower] ?: 0) > 1) return@forEachIndexed
            if (!seen.add(lower)) return@forEachIndexed

            var score = raw.length
            val firstWord = position == 0
            val capitalized = raw.first().isUpperCase()
            if (capitalized && !firstWord) score += 4      // likely a name or term
            if (firstWord && !capitalized) score -= 2
            if (raw.contains('-')) score -= 1
            if (lower.endsWith("'s")) score -= 2

            ranked += Keyword(raw, lower, match.range.first, score, firstWord)
        }
        return ranked.sortedByDescending { it.score }
    }

    /** Replace one occurrence of [keyword] with [BLANK], using its exact index. */
    private fun blankOut(sentence: String, keyword: Keyword): String =
        sentence.substring(0, keyword.index) + BLANK +
            sentence.substring(keyword.index + keyword.display.length)

    /** Replace one occurrence of [keyword] with another word. */
    private fun swapOut(sentence: String, keyword: Keyword, replacement: String): String =
        sentence.substring(0, keyword.index) + replacement +
            sentence.substring(keyword.index + keyword.display.length)

    /**
     * Pick [count] distinct distractors for [correct], preferring words of a
     * similar length so the choice cannot be guessed from shape alone.
     */
    private fun pickDistractors(
        correct: Keyword,
        pool: List<Keyword>,
        rnd: Random,
        count: Int = 3
    ): List<Keyword> {
        val candidates = pool.asSequence()
            .filter { it.lower != correct.lower }
            .distinctBy { it.lower }
            .sortedBy { abs(it.display.length - correct.display.length) }
            .toList()

        // Shuffle inside the closest band, then fall back to the rest.
        val near = candidates.take(12).shuffled(rnd)
        val far = candidates.drop(12).shuffled(rnd)

        val picked = mutableListOf<Keyword>()
        for (candidate in near + far) {
            if (picked.size >= count) break
            if (candidate.lower == correct.lower) continue
            picked += candidate
        }
        return picked
    }

    /**
     * Crude singular/plural test used to keep a falsified statement grammatical:
     * "temperatures exceed" should not become "availability exceed".
     */
    private fun isPluralForm(word: String): Boolean {
        val lower = word.lowercase(Locale.ROOT)
        return lower.endsWith("s") &&
            !lower.endsWith("ss") &&
            !lower.endsWith("us") &&
            !lower.endsWith("is")
    }

    // ── Question builders ────────────────────────────────────────────────────

    private fun makeMultipleChoice(
        sentence: String,
        keywords: List<Keyword>,
        pool: List<Keyword>,
        rnd: Random
    ): Question? {
        val key = keywords.firstOrNull() ?: return null
        val wrong = pickDistractors(key, pool, rnd).map { it.option }
        if (wrong.size < 3) return null

        val options = (wrong + key.option).shuffled(rnd)
        // Guard against any accidental duplicate before it reaches the UI.
        if (options.map { it.lowercase(Locale.ROOT) }.toSet().size != options.size) return null

        val correctIndex = options.indexOfFirst { it.equals(key.option, ignoreCase = true) }
        if (correctIndex < 0) return null

        return Question.MultipleChoice(
            id = newId(),
            difficulty = if (key.score >= 12) "easy" else "medium",
            explanation = "From the source: \u201C$sentence\u201D",
            questionText = blankOut(sentence, key),
            options = options,
            correctAnswer = correctIndex
        )
    }

    private fun makeTrueFalse(
        sentence: String,
        keywords: List<Keyword>,
        pool: List<Keyword>,
        rnd: Random
    ): Question? {
        val key = keywords.firstOrNull() ?: return null

        if (!rnd.nextBoolean()) {
            return Question.TrueFalse(
                id = newId(),
                difficulty = "easy",
                explanation = "Stated directly in the source.",
                statement = sentence,
                correct = true
            )
        }

        // Falsify by swapping the keyword for an unrelated word from the text,
        // preferring one with the same number so the sentence still parses.
        val swaps = pickDistractors(key, pool, rnd, count = 6)
        val sameNumber = swaps.filter { isPluralForm(it.display) == isPluralForm(key.display) }
        val replacement = (sameNumber.ifEmpty { swaps })
            .firstOrNull { it.lower != key.lower }
            ?: return null
        val statement = swapOut(sentence, key, replacement.option)
        if (statement == sentence) return null

        return Question.TrueFalse(
            id = newId(),
            difficulty = "medium",
            explanation = "The source says: \u201C$sentence\u201D",
            statement = statement,
            correct = false
        )
    }

    private fun makeCloze(sentence: String, keywords: List<Keyword>): Question? {
        val key = keywords.firstOrNull() ?: return null
        val first = key.display.first().uppercaseChar()
        return Question.Cloze(
            id = newId(),
            difficulty = if (key.score >= 12) "easy" else "medium",
            explanation = "From the source: \u201C$sentence\u201D",
            questionText = blankOut(sentence, key),
            clue = "$first, ${key.display.length} letters",
            answer = key.display
        )
    }

    /**
     * Definition patterns only: "X is Y", "X refers to Y", "X means Y".
     * A pronoun subject is useless as a question, so it is rejected.
     */
    private val DEFINITION = Regex(
        """^(.{3,70}?)\s+(is defined as|are known as|refers to|means|is|are|was|were)\s+(.{15,})$""",
        RegexOption.IGNORE_CASE
    )
    private val PRONOUN_SUBJECT = Regex(
        """^(it|this|that|these|those|they|he|she|we|you|there|such|his|her|their|its|our|your)\b""",
        RegexOption.IGNORE_CASE
    )

    private fun makeShortAnswer(sentence: String): Question? {
        val match = DEFINITION.find(sentence.trim()) ?: return null
        val subject = match.groupValues[1].trim()
        val verb = match.groupValues[2].lowercase(Locale.ROOT)
        val answer = match.groupValues[3].trim().trimEnd('.', '!', '?')

        if (subject.length < 3) return null
        if (subject.split(Regex("""\s+""")).size > MAX_SUBJECT_WORDS) return null
        if (PRONOUN_SUBJECT.containsMatchIn(subject)) return null
        if (answer.length < 15) return null
        if (answer.split(Regex("""\s+""")).size < 3) return null

        val question = when (verb) {
            "means" -> "What does \u201C$subject\u201D mean?"
            "refers to" -> "What does \u201C$subject\u201D refer to?"
            "are", "were" -> "What are \u201C$subject\u201D?"
            else -> "What is \u201C$subject\u201D?"
        }

        return Question.ShortAnswer(
            id = newId(),
            difficulty = "medium",
            explanation = "From the source: \u201C$sentence\u201D",
            questionText = question,
            modelAnswer = answer
        )
    }

    private fun generateFor(
        type: QuestionType,
        sentence: String,
        keywords: List<Keyword>,
        pool: List<Keyword>,
        rnd: Random
    ): Question? = when (type) {
        QuestionType.MultipleChoice -> makeMultipleChoice(sentence, keywords, pool, rnd)
        QuestionType.TrueFalse -> makeTrueFalse(sentence, keywords, pool, rnd)
        QuestionType.Cloze -> makeCloze(sentence, keywords)
        QuestionType.ShortAnswer -> makeShortAnswer(sentence)
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Build up to [count] questions from [text].
     *
     * Types are rotated so a quiz mixes formats, and each sentence can
     * contribute at most two questions (built from different keywords). If the
     * text yields fewer usable sentences the result is simply shorter — nothing
     * is padded with placeholders.
     *
     * @param seed pass a fixed value for reproducible output (used by tests).
     */
    fun buildQuestions(
        text: String,
        types: List<QuestionType> = ALL_TYPES,
        count: Int = 10,
        seed: Long? = null
    ): List<Question> {
        if (count <= 0) return emptyList()

        val rnd = seed?.let { Random(it) } ?: Random.Default
        val sentences = splitIntoSentences(text).filter(::isContentSentence)
        if (sentences.isEmpty()) return emptyList()

        val pool = sentences
            .flatMap { rankKeywords(it) }
            .distinctBy { it.lower }

        val rotation = types.ifEmpty { ALL_TYPES }
        val work = sentences
            .flatMap { sentence -> rankKeywords(sentence).take(2).map { sentence to it } }
            .shuffled(rnd)

        val questions = mutableListOf<Question>()
        val usedPrompts = mutableSetOf<String>()
        var cursor = 0

        for ((sentence, keyword) in work) {
            if (questions.size >= count) break

            for (offset in rotation.indices) {
                val type = rotation[(cursor + offset) % rotation.size]
                val question = generateFor(type, sentence, listOf(keyword), pool, rnd) ?: continue
                if (!usedPrompts.add(promptKey(question))) continue

                questions += question
                cursor = (cursor + offset + 1) % rotation.size
                break
            }
        }

        return questions
    }

    /** Assemble a [StudyMode] from raw material. */
    fun buildStudyMode(
        sourceLabel: String,
        text: String,
        sourceType: SourceType,
        count: Int = 10,
        seed: Long? = null
    ): StudyMode = StudyMode(
        source = sourceLabel,
        text = text,
        questions = buildQuestions(text, ALL_TYPES, count, seed),
        sourceType = sourceType
    )

    // ── Answer checking ──────────────────────────────────────────────────────

    /**
     * Score one answer against its question. Used by the quiz screen so the
     * scoring rules live in one place and can be tested.
     */
    fun isCorrect(question: Question, answer: Any?): Boolean = when (question) {
        is Question.MultipleChoice -> answer is Int && answer == question.correctAnswer
        is Question.TrueFalse -> answer is Boolean && answer == question.correct
        is Question.ShortAnswer -> answer is String && matchesTyped(answer, question.modelAnswer)
        is Question.Cloze -> answer is String && matchesTyped(answer, question.answer)
    }

    /**
     * Compare a typed answer with the expected one, ignoring case, punctuation
     * and spacing. A short lead-in is tolerated ("the process of X" for "X"),
     * but a long rambling answer is not accepted just because it contains it.
     */
    private fun matchesTyped(given: String, expected: String): Boolean {
        val g = normalizeAnswer(given)
        val e = normalizeAnswer(expected)
        if (g.isEmpty() || e.isEmpty()) return false
        if (g == e) return true
        return g.contains(e) && g.length <= e.length + 24
    }

    /** Lowercase, strip punctuation, collapse whitespace. */
    fun normalizeAnswer(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun promptKey(question: Question): String = when (question) {
        is Question.MultipleChoice -> "mcq:${question.questionText}"
        is Question.TrueFalse -> "tf:${question.statement}"
        is Question.ShortAnswer -> "sa:${question.questionText}"
        is Question.Cloze -> "cl:${question.questionText}"
    }

    /** Trim/normalise extracted prose without changing its meaning. */
    fun prepareProse(text: String): String =
        text.split(Regex("""\r?\n"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private fun newId(): String = java.util.UUID.randomUUID().toString()
}
