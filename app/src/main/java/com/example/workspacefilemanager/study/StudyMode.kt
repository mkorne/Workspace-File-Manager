package com.example.workspacefilemanager.study

/**
 * passYourExam on the workspace manager.
 *
 * Content type: a file the user has opened in the Workspace editor (or any text
 * the user pastes). The engine produces three question types from the material,
 * keeping everything local -- no network, no API key, no backend.
 *
 * Question datatypes match passYourExam's contract, but are flat and
 * self-contained so the UI can render them without a backend:
 *
 *   multiple_choice  -> one item, four options, one correct index
 *   true_false       -> one statement, correct boolean
 *   short_answer     -> one question, no auto-graded answer
 *   cloze            -> a sentence with a blank ("_____")
 */
data class StudyMode(
    /** File the student is studying. */ val source: String,
    /** The cleaned study material. */ val text: String,
    /** The questions that were generated from the material. */ val questions: List<Question>,
    /** Whether the source was a file or pasted text. */ val sourceType: SourceType,
)

enum class SourceType { File, Paste }

sealed class Question {
    abstract val id: String
    abstract val type: QuestionType
    abstract val difficulty: String
    abstract val explanation: String

    data class MultipleChoice(
        override val id: String,
        override val type: QuestionType = QuestionType.MultipleChoice,
        override val difficulty: String = "medium",
        override val explanation: String = "",
        val questionText: String,
        val options: List<String>,
        val correctAnswer: Int,
    ) : Question()

    data class TrueFalse(
        override val id: String,
        override val type: QuestionType = QuestionType.TrueFalse,
        override val difficulty: String = "medium",
        override val explanation: String = "",
        val statement: String,
        val correct: Boolean,
    ) : Question()

    data class ShortAnswer(
        override val id: String,
        override val type: QuestionType = QuestionType.ShortAnswer,
        override val difficulty: String = "medium",
        override val explanation: String = "",
        val questionText: String,
        val modelAnswer: String,
    ) : Question()

    data class Cloze(
        override val id: String,
        override val type: QuestionType = QuestionType.Cloze,
        override val difficulty: String = "medium",
        override val explanation: String = "",
        val questionText: String,
        val clue: String,
        val answer: String,
    ) : Question()
}

enum class QuestionType {
    MultipleChoice, TrueFalse, ShortAnswer, Cloze
}

fun Question.typeLabel(): String = when (type) {
    QuestionType.MultipleChoice -> "Multiple Choice"
    QuestionType.TrueFalse -> "True / False"
    QuestionType.ShortAnswer -> "Short Answer"
    QuestionType.Cloze -> "Cloze"
}
