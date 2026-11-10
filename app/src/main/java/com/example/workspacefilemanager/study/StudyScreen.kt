package com.example.workspacefilemanager.study

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Maximum characters read from a picked file; keeps a huge file from OOMing. */
private const val MAX_SOURCE_CHARS = 200_000

private val CorrectGreen = Color(0xFF2E7D32)
private val CorrectGreenContainer = Color(0xFFD7F0D9)
private val WrongRedContainer = Color(0xFFFFDAD6)

/**
 * Study mode: turn a document (or pasted text) into a local quiz.
 *
 * Everything is generated on the device by [StudyEngine]; nothing is uploaded.
 * The screen is reachable from the Workspace editor, which passes the current
 * document in as [initialText].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(
    initialTitle: String? = null,
    initialText: String? = null,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var source by remember {
        mutableStateOf(if (initialText.isNullOrBlank()) SourceType.File else SourceType.Paste)
    }
    var material by remember { mutableStateOf(initialText.orEmpty()) }
    var fileLabel by remember { mutableStateOf("") }
    var quiz by remember { mutableStateOf<StudyMode?>(null) }
    var answers by remember { mutableStateOf<Map<String, Any>>(emptyMap()) }
    var submitted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun generate(seed: Long = System.currentTimeMillis()) {
        if (material.isBlank()) {
            message = "Choose a file or paste some text first."
            return
        }
        busy = true
        message = null
        val sourceLabel = if (source == SourceType.File) {
            fileLabel.ifBlank { "Selected file" }
        } else {
            initialTitle?.takeIf { it.isNotBlank() } ?: "Pasted text"
        }
        val text = material
        val type = source
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                StudyEngine.buildStudyMode(sourceLabel, text, type, count = 10, seed = seed)
            }
            busy = false
            if (result.questions.isEmpty()) {
                quiz = null
                message = "No questions could be built from this text. It needs a few full sentences of prose."
            } else {
                quiz = result
                answers = emptyMap()
                submitted = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val text = withContext(Dispatchers.IO) { readTextSource(context, uri) }
            busy = false
            if (text.isNullOrBlank()) {
                message = "That file is not plain text. Try .txt, .md, .csv or .json."
            } else {
                material = text
                fileLabel = displayName(context, uri)
                source = SourceType.File
                quiz = null
                answers = emptyMap()
                submitted = false
                message = "Loaded ${text.length} characters. Tap Generate questions."
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Study", fontWeight = FontWeight.Bold, fontSize = 22.sp) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                actions = {
                    androidx.compose.material3.IconButton(
                        onClick = { generate() },
                        enabled = !busy && material.isNotBlank()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Regenerate questions")
                    }
                    androidx.compose.material3.IconButton(
                        onClick = {
                            quiz = null
                            answers = emptyMap()
                            submitted = false
                            message = null
                        }
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear quiz")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SourceCard(
                    source = source,
                    label = fileLabel,
                    material = material,
                    busy = busy,
                    onSourceChange = { source = it },
                    onPickFile = {
                        source = SourceType.File
                        picker.launch(
                            arrayOf(
                                "text/*",
                                "application/json",
                                "application/xml",
                                "application/x-yaml"
                            )
                        )
                    },
                    onTextChange = { material = it }
                )
            }

            item {
                Button(
                    onClick = { generate() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && material.isNotBlank()
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (quiz == null) "Generate questions" else "Generate new questions")
                }
            }

            if (busy) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }

            message?.let { text ->
                item {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val current = quiz
            if (current == null) {
                item { EmptyState() }
            } else {
                item {
                    Column {
                        Text("Generated questions", fontWeight = FontWeight.Bold)
                        Text(
                            "${current.source} · ${current.questions.size} questions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(items = current.questions, key = { it.id }) { question ->
                    QuestionCard(
                        question = question,
                        selected = answers[question.id],
                        submitted = submitted,
                        onAnswer = { value -> answers = answers + (question.id to value) }
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { submitted = true },
                            modifier = Modifier.weight(1f),
                            enabled = !submitted && answers.size == current.questions.size
                        ) {
                            Text("Submit & score")
                        }
                        OutlinedButton(
                            onClick = {
                                answers = emptyMap()
                                submitted = false
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Reset")
                        }
                    }
                }

                if (submitted) {
                    item { ScoreCard(current, answers) }
                }
            }
        }
    }
}

// ── Source selection ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceCard(
    source: SourceType,
    label: String,
    material: String,
    busy: Boolean,
    onSourceChange: (SourceType) -> Unit,
    onPickFile: () -> Unit,
    onTextChange: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Source", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = source == SourceType.File,
                    onClick = { onSourceChange(SourceType.File) },
                    label = { Text("File") }
                )
                FilterChip(
                    selected = source == SourceType.Paste,
                    onClick = { onSourceChange(SourceType.Paste) },
                    label = { Text("Paste text") }
                )
            }

            Spacer(Modifier.height(10.dp))

            if (source == SourceType.File) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy, onClick = onPickFile)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            label.ifBlank { "Choose a text file" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (material.isBlank()) "Tap to browse"
                            else "${material.length} characters loaded",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = material,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Paste your study material here…") },
                    minLines = 4,
                    maxLines = 8
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.School,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick a source, then generate questions",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Questions are built on this device from the text you provide.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Question card ─────────────────────────────────────────────────────────

@Composable
fun QuestionCard(
    question: Question,
    selected: Any?,
    submitted: Boolean,
    onAnswer: (Any) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    question.typeLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    question.difficulty,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = when (question) {
                    is Question.MultipleChoice -> question.questionText
                    is Question.TrueFalse -> question.statement
                    is Question.ShortAnswer -> question.questionText
                    is Question.Cloze -> question.questionText
                },
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(10.dp))

            when (question) {
                is Question.MultipleChoice -> {
                    question.options.forEachIndexed { index, option ->
                        OptionRow(
                            number = index + 1,
                            text = option,
                            isSelected = selected == index,
                            isCorrect = index == question.correctAnswer,
                            submitted = submitted,
                            onClick = { if (!submitted) onAnswer(index) }
                        )
                    }
                }

                is Question.TrueFalse -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TrueFalseChip(
                            text = "True",
                            selected = selected == true,
                            isCorrect = question.correct,
                            submitted = submitted,
                            onClick = { if (!submitted) onAnswer(true) }
                        )
                        TrueFalseChip(
                            text = "False",
                            selected = selected == false,
                            isCorrect = !question.correct,
                            submitted = submitted,
                            onClick = { if (!submitted) onAnswer(false) }
                        )
                    }
                }

                is Question.Cloze -> {
                    Text(
                        "Clue: ${question.clue}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    TypedAnswerField(
                        value = selected as? String,
                        submitted = submitted,
                        onValueChange = onAnswer
                    )
                }

                is Question.ShortAnswer -> {
                    TypedAnswerField(
                        value = selected as? String,
                        submitted = submitted,
                        onValueChange = onAnswer
                    )
                }
            }

            if (submitted) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                val correct = StudyEngine.isCorrect(question, selected)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (correct) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = CorrectGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        if (correct) "Correct" else "Not quite",
                        color = if (correct) CorrectGreen else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }

                when (question) {
                    is Question.Cloze -> AnswerLine("Answer", question.answer)
                    is Question.ShortAnswer -> AnswerLine("Model answer", question.modelAnswer)
                    else -> Unit
                }

                if (question.explanation.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        question.explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    number: Int,
    text: String,
    isSelected: Boolean,
    isCorrect: Boolean,
    submitted: Boolean,
    onClick: () -> Unit,
) {
    val background = when {
        submitted && isCorrect -> CorrectGreenContainer
        submitted && isSelected && !isCorrect -> WrongRedContainer
        isSelected -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(background, RoundedCornerShape(8.dp))
            .clickable(enabled = !submitted, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$number.", fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrueFalseChip(
    text: String,
    selected: Boolean,
    isCorrect: Boolean,
    submitted: Boolean,
    onClick: () -> Unit,
) {
    val colors = when {
        submitted && isCorrect -> FilterChipDefaults.filterChipColors(
            containerColor = CorrectGreenContainer,
            labelColor = CorrectGreen
        )
        submitted && selected && !isCorrect -> FilterChipDefaults.filterChipColors(
            containerColor = WrongRedContainer,
            labelColor = MaterialTheme.colorScheme.error
        )
        else -> FilterChipDefaults.filterChipColors()
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = !submitted,
        label = { Text(text) },
        colors = colors
    )
}

@Composable
private fun TypedAnswerField(
    value: String?,
    submitted: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value.orEmpty(),
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !submitted,
        singleLine = true,
        placeholder = { Text("Type your answer…") }
    )
}

@Composable
private fun AnswerLine(label: String, value: String) {
    Spacer(Modifier.height(4.dp))
    Text(
        "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium
    )
}

// ── Score ─────────────────────────────────────────────────────────────────

@Composable
private fun ScoreCard(quiz: StudyMode, answers: Map<String, Any>) {
    val total = quiz.questions.size
    val correct = quiz.questions.count { StudyEngine.isCorrect(it, answers[it.id]) }
    val percent = if (total == 0) 0 else correct * 100 / total
    val grade = when {
        percent >= 90 -> "Outstanding"
        percent >= 75 -> "Great"
        percent >= 60 -> "Getting there"
        else -> "Keep practising"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "Score: $correct / $total  ($percent%)",
                fontWeight = FontWeight.Bold
            )
            Text("Grade: $grade", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Answers are checked against the source text, not a model.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── File helpers ──────────────────────────────────────────────────────────

/** Read a picked document as text, rejecting binary content and capping size. */
private fun readTextSource(context: Context, uri: Uri): String? = try {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
        val builder = StringBuilder()
        val buffer = CharArray(8_192)
        var total = 0
        while (total < MAX_SOURCE_CHARS) {
            val read = reader.read(buffer)
            if (read < 0) break
            builder.append(buffer, 0, read)
            total += read
        }
        val text = builder.toString()
        val control = text.count { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
        if (text.contains('\u0000') || control > text.length / 20) null else text
    }
} catch (t: Throwable) {
    null
}

/** Human-readable file name for a content URI. */
private fun displayName(context: Context, uri: Uri): String = try {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "Selected file"
} catch (t: Throwable) {
    uri.lastPathSegment?.substringAfterLast('/') ?: "Selected file"
}
