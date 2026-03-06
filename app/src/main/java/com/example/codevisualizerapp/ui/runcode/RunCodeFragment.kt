package com.example.codevisualizerapp.ui.runcode

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.codevisualizerapp.R
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Callback
import okhttp3.Call
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

// ------------------- REMOTE BACKEND URL ------------------

private const val SERVER_URL = "https://code-visualizer-backend.onrender.com/run"
private const val HEALTH_URL = "https://code-visualizer-backend.onrender.com/health"

// ---------------------------------------------------------

/* =========================
   Helper data classes
   ========================= */

data class StepEvent(
    val lineNo: Int,
    val codeLine: String,
    val desc: String,
    val varsSnapshot: Map<String, String>,
    val outputs: List<String>,
    val visualizations: List<String> = emptyList()
)

/* =========================
   Local Mini Interpreter
   (unchanged logic, used only for SIMPLE code)
   ========================= */

class ExprEvaluator(private val vars: Map<String, Any?>) {

    fun evalExpr(expr: String): Any? {
        val s = expr.trim()
        if (s.isEmpty()) return null

        // ---------- NEW: basic Python-style f-string support ----------
        if ((s.startsWith("f\"") && s.endsWith("\"")) ||
            (s.startsWith("f'") && s.endsWith("'"))
        ) {
            // strip the leading f" / f' and trailing quote
            val body = s.substring(2, s.length - 1)
            return evalFString(body)
        }
        // --------------------------------------------------------------

        if (s.contains('.')) {
            val parts = s.split('.')
            if (parts.size == 2) {
                val objName = parts[0].trim()
                val methodCall = parts[1].trim()
                if (methodCall.contains('(') && methodCall.endsWith(')')) {
                    val methodName = methodCall.substringBefore('(')
                    val args = methodCall.substringAfter('(').removeSuffix(")")
                    val obj = vars[objName]
                    return handleMethodCall(obj, methodName, args, objName)
                }
            }
        }
        if (s.contains('(') && s.endsWith(')') && !s.startsWith('[')) {
            val funcName = s.substringBefore('(')
            val args = s.substringAfter('(').removeSuffix(")")
            return handleFunctionCall(funcName, args)
        }
        if (s.startsWith("[") && s.endsWith("]")) {
            val inner = s.substring(1, s.length - 1)
            if (inner.trim().isEmpty()) return mutableListOf<Any?>()
            val parts = splitTopLevel(inner, ',')
            return parts.map { evalExpr(it.trim()) }.toMutableList()
        }
        val idxMatch = Regex("""^(.+)\[(.+)]$""").find(s)
        if (idxMatch != null) {
            val arrExpr = idxMatch.groupValues[1].trim()
            val idxExpr = idxMatch.groupValues[2].trim()
            val arrVal = evalExpr(arrExpr)
            val idxVal = evalExpr(idxExpr)
            if (arrVal is MutableList<*> && idxVal is Number) {
                val idx = idxVal.toInt()
                return if (idx >= 0 && idx < arrVal.size) arrVal[idx] else null
            }
        }
        if (s.contains(">=")) {
            val parts = s.split(">=")
            if (parts.size == 2) {
                val left = evalExpr(parts[0].trim()) as? Number
                val right = evalExpr(parts[1].trim()) as? Number
                return if (left != null && right != null) left.toLong() >= right.toLong() else false
            }
        }
        if (s.contains("<=")) {
            val parts = s.split("<=")
            if (parts.size == 2) {
                val left = evalExpr(parts[0].trim()) as? Number
                val right = evalExpr(parts[1].trim()) as? Number
                return if (left != null && right != null) left.toLong() <= right.toLong() else false
            }
        }
        if (s.contains(">") && !s.contains(">=")) {
            val parts = s.split(">")
            if (parts.size == 2) {
                val left = evalExpr(parts[0].trim()) as? Number
                val right = evalExpr(parts[1].trim()) as? Number
                return if (left != null && right != null) left.toLong() > right.toLong() else false
            }
        }
        if (s.contains("<") && !s.contains("<=")) {
            val parts = s.split("<")
            if (parts.size == 2) {
                val left = evalExpr(parts[0].trim()) as? Number
                val right = evalExpr(parts[1].trim()) as? Number
                return if (left != null && right != null) left.toLong() < right.toLong() else false
            }
        }
        if (s.matches(Regex("^-?\\d+$"))) {
            return s.toInt()
        }
        if (s.matches(Regex("""^".*"$""")) || s.matches(Regex("""^'.*'$"""))) {
            return s.substring(1, s.length - 1)
        }
        if (vars.containsKey(s)) return vars[s]
        return evalArithmetic(s)
    }

    // ---------- NEW helper for f-strings ----------
    private fun evalFString(body: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '{') {
                val close = body.indexOf('}', i + 1)
                if (close == -1) {
                    // no matching }, just append the rest
                    sb.append(body.substring(i))
                    break
                }
                val exprInside = body.substring(i + 1, close)
                val value = evalExpr(exprInside.trim())
                val asText = when (value) {
                    null -> "null"
                    is String -> value
                    is List<*> -> value.toString()
                    else -> value.toString()
                }
                sb.append(asText)
                i = close + 1
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
    // ---------------------------------------------

    private fun handleMethodCall(obj: Any?, methodName: String, args: String, objName: String): Any? {
        when (obj) {
            is MutableList<*> -> {
                val list = obj as MutableList<Any?>
                when (methodName) {
                    "append", "push" -> {
                        val value = if (args.isNotEmpty()) evalExpr(args) else null
                        list.add(value)
                        return value
                    }
                    "pop" -> return if (list.isNotEmpty()) list.removeAt(list.size - 1) else null
                    "peek", "top" -> return if (list.isNotEmpty()) list.last() else null
                    "dequeue" -> return if (list.isNotEmpty()) list.removeAt(0) else null
                    "enqueue" -> {
                        val value = if (args.isNotEmpty()) evalExpr(args) else null
                        list.add(value)
                        return value
                    }
                    "isEmpty" -> return list.isEmpty()
                    "size" -> return list.size
                }
            }
        }
        return null
    }

    private fun handleFunctionCall(funcName: String, args: String): Any? {
        return when (funcName) {
            "len" -> {
                when (val obj = evalExpr(args)) {
                    is List<*> -> obj.size
                    is String -> obj.length
                    else -> 0
                }
            }
            "range" -> (evalExpr(args) as? Number)?.toInt() ?: 0
            "min" -> {
                if (args.contains(',')) {
                    val parts = splitTopLevel(args, ',')
                    parts.map { evalExpr(it.trim()) as? Number }.filterNotNull()
                        .minByOrNull { it.toLong() }?.toLong()
                } else {
                    (evalExpr(args) as? List<*>)?.mapNotNull { it as? Number }
                        ?.minByOrNull { it.toLong() }?.toLong()
                }
            }
            "max" -> {
                if (args.contains(',')) {
                    val parts = splitTopLevel(args, ',')
                    parts.map { evalExpr(it.trim()) as? Number }.filterNotNull()
                        .maxByOrNull { it.toLong() }?.toLong()
                } else {
                    (evalExpr(args) as? List<*>)?.mapNotNull { it as? Number }
                        ?.maxByOrNull { it.toLong() }?.toLong()
                }
            }
            else -> null
        }
    }

    private fun splitTopLevel(s: String, delim: Char): List<String> {
        val result = ArrayList<String>()
        var depth = 0
        var cur = StringBuilder()
        for (ch in s) {
            when (ch) {
                '[', '(' -> depth++
                ']', ')' -> depth--
            }
            if (ch == delim && depth == 0) {
                result.add(cur.toString())
                cur = StringBuilder()
            } else {
                cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) result.add(cur.toString())
        return result
    }

    private fun evalArithmetic(expr: String): Any? {
        val tokens = tokenize(expr)
        if (tokens.isEmpty()) return null
        if (tokens.size == 1) return evalExpr(tokens[0])
        val out = mutableListOf<String>()
        val ops = Stack<String>()
        val prec = mapOf("+" to 1, "-" to 1, "*" to 2, "/" to 2, "%" to 2)
        for (t in tokens) {
            when {
                t.matches(Regex("^-?\\d+$")) || vars.containsKey(t) -> out.add(t)
                t == "(" -> ops.push(t)
                t == ")" -> {
                    while (ops.isNotEmpty() && ops.peek() != "(") out.add(ops.pop())
                    if (ops.isNotEmpty() && ops.peek() == "(") ops.pop()
                }
                prec.containsKey(t) -> {
                    while (ops.isNotEmpty() && ops.peek() != "(" &&
                        (prec[ops.peek()] ?: 0) >= (prec[t] ?: 0)
                    ) {
                        out.add(ops.pop())
                    }
                    ops.push(t)
                }
                else -> out.add(t)
            }
        }
        while (ops.isNotEmpty()) out.add(ops.pop())
        val st = Stack<Any?>()
        for (tok in out) {
            when {
                tok.matches(Regex("^-?\\d+$")) -> st.push(tok.toInt())
                vars.containsKey(tok) -> st.push(vars[tok])
                tok in setOf("+", "-", "*", "/", "%") -> {
                    if (st.size >= 2) {
                        val b = st.pop()
                        val a = st.pop()
                        st.push(arithmeticOp(a, b, tok))
                    }
                }
                else -> st.push(null)
            }
        }
        return if (st.isEmpty()) null else st.pop()
    }

    private fun arithmeticOp(a: Any?, b: Any?, op: String): Any? {
        val an = a as? Number
        val bn = b as? Number
        if (an == null || bn == null) return null
        return when (op) {
            "+" -> an.toLong() + bn.toLong()
            "-" -> an.toLong() - bn.toLong()
            "*" -> an.toLong() * bn.toLong()
            "/" -> if (bn.toLong() == 0L) null else an.toLong() / bn.toLong()
            "%" -> if (bn.toLong() == 0L) null else an.toLong() % bn.toLong()
            else -> null
        }
    }

    private fun tokenize(s: String): List<String> {
        val res = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '-' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                    val sb = StringBuilder().append(c)
                    i++
                    while (i < s.length && s[i].isDigit()) sb.append(s[i++])
                    res.add(sb.toString())
                }
                c.isLetter() || c == '_' -> {
                    val sb = StringBuilder().append(c)
                    i++
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) sb.append(s[i++])
                    res.add(sb.toString())
                }
                c in "+-*/%()[]" -> {
                    res.add(c.toString())
                    i++
                }
                else -> i++
            }
        }
        return res
    }
}


class MiniInterpreter(private val rawCode: String) {
    private val lines = rawCode.lines()
    private var pc = 0
    val steps = ArrayList<StepEvent>()
    private val outputs = ArrayList<String>()
    private val vars: MutableMap<String, Any?> = HashMap()

    fun runAll() {
        steps.clear()
        outputs.clear()
        vars.clear()
        pc = 0
        val n = lines.size
        while (pc < n) {
            if (pc in 0 until n) executeLine(pc)
            pc++
        }
    }

    private fun executeLine(index: Int) {
        if (index >= lines.size) return
        val raw = lines[index]
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return
        when {
            trimmed.startsWith("print") -> {
                val inside = extractFunctionArgs(trimmed, "print")
                val valEval = ExprEvaluator(vars).evalExpr(inside)
                outputs.add(valEval?.toString() ?: "null")
                recordStep(index, trimmed, "Output -> ${stringify(valEval)}")
            }
            trimmed.startsWith("for ") && trimmed.contains(" in range") -> handleForLoop(index, trimmed)
            trimmed.startsWith("while ") && trimmed.endsWith(":") -> handleWhileLoop(index, trimmed)
            trimmed.startsWith("if ") && trimmed.endsWith(":") -> handleIfStatement(index, trimmed)
            trimmed == "else:" -> recordStep(index, trimmed, "Else block")
            trimmed.contains("=") && !containsComparison(trimmed) -> handleAssignment(index, trimmed)
            trimmed.contains(".swap(") -> handleSwap(index, trimmed)
            else -> try {
                ExprEvaluator(vars).evalExpr(trimmed)
                recordStep(index, trimmed, "Executed: $trimmed")
            } catch (e: Exception) {
                recordStep(index, trimmed, "Unable to execute: $trimmed")
            }
        }
    }

    private fun handleSwap(index: Int, trimmed: String) {
        val parts = trimmed.split(".swap(")
        if (parts.size == 2) {
            val arrName = parts[0].trim()
            val args = parts[1].removeSuffix(")").trim()
            val argParts = args.split(",")
            if (argParts.size == 2) {
                val i = ExprEvaluator(vars).evalExpr(argParts[0].trim()) as? Number
                val j = ExprEvaluator(vars).evalExpr(argParts[1].trim()) as? Number
                val arr = vars[arrName] as? MutableList<Any?>
                if (arr != null && i != null && j != null) {
                    val ii = i.toInt()
                    val jj = j.toInt()
                    if (ii in 0 until arr.size && jj in 0 until arr.size) {
                        val temp = arr[ii]
                        arr[ii] = arr[jj]
                        arr[jj] = temp
                        val vis = createArrayVisualization(arr, setOf(ii, jj))
                        recordStep(index, trimmed, "Swapped $arrName[$ii] ↔ $arrName[$jj]", listOf(vis))
                    }
                }
            }
        }
    }

    private fun handleAssignment(index: Int, trimmed: String) {
        val equalIndex = trimmed.indexOf('=')
        val left = trimmed.substring(0, equalIndex).trim()
        val right = trimmed.substring(equalIndex + 1).trim()
        if (left.contains('[') && left.contains(']')) {
            val arrMatch = Regex("""^(.+)\[(.+)]$""").find(left)
            if (arrMatch != null) {
                val arrName = arrMatch.groupValues[1].trim()
                val indexExpr = arrMatch.groupValues[2].trim()
                val arr = vars[arrName] as? MutableList<Any?>
                val indexVal = ExprEvaluator(vars).evalExpr(indexExpr) as? Number
                val value = ExprEvaluator(vars).evalExpr(right)
                if (arr != null && indexVal != null) {
                    val idx = indexVal.toInt()
                    if (idx in 0 until arr.size) {
                        arr[idx] = value
                        val vis = createArrayVisualization(arr, setOf(idx))
                        recordStep(index, trimmed, "Set $arrName[$idx] = ${stringify(value)}", listOf(vis))
                        return
                    }
                }
            }
        }
        val value = ExprEvaluator(vars).evalExpr(right)
        vars[left] = value
        val visualizations = mutableListOf<String>()
        if (value is MutableList<*>) {
            visualizations.add(createArrayVisualization(value))
            if (isStack(left)) visualizations.add(createStackVisualization(value))
            else if (isQueue(left)) visualizations.add(createQueueVisualization(value))
        }
        recordStep(index, trimmed, "Assign: $left = ${stringify(value)}", visualizations)
    }

    private fun handleForLoop(index: Int, trimmed: String) {
        val varName = trimmed.substringAfter("for ").substringBefore(" in range").trim()
        val rangeExpr = trimmed.substringAfter("in range").trim().removePrefix("(").removeSuffix("):")
        val range = ExprEvaluator(vars).evalExpr(rangeExpr) as? Number
        val count = range?.toInt() ?: 0
        recordStep(index, trimmed, "For loop: $varName from 0 to ${count - 1}")
        val blockLines = collectBlock(index)
        for (iter in 0 until count) {
            vars[varName] = iter
            recordStep(index, trimmed, "Loop iteration $iter: $varName = $iter")
            for (blockLine in blockLines) executeLine(blockLine)
        }
        pc = if (blockLines.isEmpty()) index else blockLines.last()
    }

    private fun handleWhileLoop(index: Int, trimmed: String) {
        val condExpr = trimmed.removePrefix("while").removeSuffix(":").trim()
        val blockLines = collectBlock(index)
        var iterations = 0
        val maxIterations = 1000
        while (iterations < maxIterations) {
            val condVal = ExprEvaluator(vars).evalExpr(condExpr)
            val truth = isTruthy(condVal)
            recordStep(index, trimmed, "While condition ($condExpr) = $truth")
            if (!truth) break
            for (blockLine in blockLines) executeLine(blockLine)
            iterations++
        }
        if (iterations >= maxIterations) recordStep(index, trimmed, "Loop terminated (max iterations reached)")
        pc = if (blockLines.isEmpty()) index else blockLines.last()
    }

    private fun handleIfStatement(index: Int, trimmed: String) {
        val condExpr = trimmed.removePrefix("if").removeSuffix(":").trim()
        val truth = isTruthy(ExprEvaluator(vars).evalExpr(condExpr))
        recordStep(index, trimmed, "If condition ($condExpr) = $truth")
        val ifBlock = collectBlock(index)
        val elseBlock = collectElseBlock(index)
        if (truth) {
            for (blockLine in ifBlock) executeLine(blockLine)
        } else if (elseBlock != null) {
            for (blockLine in elseBlock) executeLine(blockLine)
        }
        pc = when {
            truth && ifBlock.isNotEmpty() -> ifBlock.last()
            !truth && elseBlock != null && elseBlock.isNotEmpty() -> elseBlock.last()
            ifBlock.isNotEmpty() -> ifBlock.last()
            else -> index
        }
    }

    private fun createArrayVisualization(list: List<*>, highlightIndices: Set<Int> = emptySet()): String {
        val sb = StringBuilder()
        sb.append("Array: [")
        list.forEachIndexed { index, value ->
            if (index > 0) sb.append(", ")
            if (highlightIndices.contains(index)) sb.append("*${stringify(value)}*")
            else sb.append(stringify(value))
        }
        sb.append("]")
        return sb.toString()
    }

    private fun createStackVisualization(list: List<*>): String {
        val sb = StringBuilder()
        sb.append("Stack (top to bottom):\n")
        if (list.isEmpty()) sb.append("  (empty)")
        for (i in list.size - 1 downTo 0) {
            sb.append("  ${if (i == list.size - 1) "→ " else "  "}${stringify(list[i])}")
            if (i > 0) sb.append("\n")
        }
        return sb.toString()
    }

    private fun createQueueVisualization(list: List<*>): String {
        val sb = StringBuilder()
        sb.append("Queue (front to rear):\n")
        sb.append("  Front → ")
        if (list.isEmpty()) sb.append("(empty)")
        else list.forEachIndexed { index, value ->
            if (index > 0) sb.append(" → ")
            sb.append(stringify(value))
        }
        sb.append(" ← Rear")
        return sb.toString()
    }

    private fun isStack(varName: String): Boolean = varName.lowercase().contains("stack")
    private fun isQueue(varName: String): Boolean = varName.lowercase().contains("queue")

    private fun extractFunctionArgs(statement: String, funcName: String): String {
        val start = statement.indexOf('(', funcName.length)
        val end = statement.lastIndexOf(')')
        return if (start != -1 && end != -1 && end > start) statement.substring(start + 1, end) else ""
    }

    private fun containsComparison(s: String): Boolean =
        s.contains("==") || s.contains("!=") || s.contains("<=") || s.contains(">=") || s.contains("<") || s.contains(">")

    private fun collectBlock(lineIndex: Int): List<Int> {
        val result = ArrayList<Int>()
        val baseIndent = indentOf(lines[lineIndex])
        var i = lineIndex + 1
        while (i < lines.size) {
            val line = lines[i]
            if (line.trim().isEmpty()) {
                i++
                continue
            }
            val indent = indentOf(line)
            if (indent <= baseIndent) break
            result.add(i++)
        }
        return result
    }

    private fun collectElseBlock(lineIndex: Int): List<Int>? {
        val ifBlock = collectBlock(lineIndex)
        val nextIndex = if (ifBlock.isEmpty()) lineIndex + 1 else ifBlock.last() + 1
        if (nextIndex >= lines.size) return null
        val nextLine = lines[nextIndex]
        return if (nextLine.trim() == "else:" && indentOf(nextLine) == indentOf(lines[lineIndex])) {
            collectBlock(nextIndex)
        } else null
    }

    private fun indentOf(s: String): Int {
        var i = 0
        while (i < s.length && s[i] == ' ') i++
        return i
    }

    private fun isTruthy(v: Any?): Boolean {
        return when (v) {
            null -> false
            is Boolean -> v
            is Number -> v.toLong() != 0L
            is String -> v.isNotEmpty()
            is List<*> -> v.isNotEmpty()
            else -> true
        }
    }

    private fun stringify(v: Any?): String {
        return when (v) {
            null -> "null"
            is String -> "\"$v\""
            is List<*> -> v.toString()
            else -> v.toString()
        }
    }

    private fun recordStep(
        lineIndex: Int,
        codeLine: String,
        desc: String,
        visualizations: List<String> = emptyList()
    ) {
        val snap = HashMap<String, String>()
        for ((k, v) in vars) snap[k] = stringify(v)
        steps.add(
            StepEvent(
                lineIndex + 1,
                codeLine.trim(),
                desc,
                snap,
                ArrayList(outputs),
                visualizations
            )
        )
    }
}

/* =========================
   RunCodeFragment (hybrid)
   ========================= */

class RunCodeFragment : Fragment() {

    private lateinit var codeInput: EditText
    private lateinit var runButton: Button
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button
    private lateinit var fastForwardButton: Button
    private lateinit var highlightedCode: TextView
    private lateinit var varsView: TextView
    private lateinit var stepCounter: TextView

    private var currentSteps: List<StepEvent> = emptyList()
    private var currentIndex = 0
    private var autoPlay = false
    private val handler = Handler(Looper.getMainLooper())

    private val autoplayRunnable = object : Runnable {
        override fun run() {
            if (autoPlay && currentIndex < currentSteps.size - 1) {
                currentIndex++
                showStep(currentIndex)
                handler.postDelayed(this, 800)
            } else {
                autoPlay = false
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_run_code, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar_run)
        toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_save_file -> {
                    showSaveDialog()
                    true
                }
                else -> false
            }
        }

        setDefaultCode()

        val incomingCode = arguments?.getString("CODE_CONTENT")
        if (incomingCode != null) {
            codeInput.setText(incomingCode)
        }

        setupClickListeners()
    }

    private fun initializeViews(view: View) {
        codeInput = view.findViewById(R.id.codeInput)
        runButton = view.findViewById(R.id.runButton)
        prevButton = view.findViewById(R.id.prevButton)
        nextButton = view.findViewById(R.id.nextButton)
        fastForwardButton = view.findViewById(R.id.fastForwardButton)
        highlightedCode = view.findViewById(R.id.highlightedCode)
        varsView = view.findViewById(R.id.varsView)
        stepCounter = view.findViewById(R.id.stepCounter)
    }

    private fun setupClickListeners() {
        runButton.setOnClickListener {
            runSmart(jumpToEnd = false)
        }

        fastForwardButton.setOnClickListener {
            runSmart(jumpToEnd = true)
        }

        prevButton.setOnClickListener {
            if (currentIndex > 0) {
                currentIndex--
                showStep(currentIndex)
            }
        }

        nextButton.setOnClickListener {
            if (currentIndex < currentSteps.size - 1) {
                currentIndex++
                showStep(currentIndex)
            } else {
                toggleAutoplay()
            }
        }
    }

    // Optional helper if you later want local dev server again
    @Suppress("unused")
    private fun getServerUrlForCurrentDevice(): String {
        val isEmulator = (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.contains("vbox")
                || Build.FINGERPRINT.contains("emulator")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86"))
        return if (isEmulator) "http://10.0.2.2:8000/run" else SERVER_URL
    }

    /* ---------- Offline/online helpers ---------- */

    // Code is "simple" => safe for local interpreter
    private fun isSimpleCode(code: String): Boolean {
        val lowered = code.lowercase()

        val advancedPatterns = listOf(
            "def ",        // functions
            "return ",     // returns
            "class ",      // classes
            " lambda",     // lambdas
            "try:",        // try/except
            "except ",     // exception handling
            "with ",       // context managers
            "import ",     // imports
            "from "        // from imports
        )

        return advancedPatterns.none { pattern -> lowered.contains(pattern) }
    }

    private fun isNetworkError(msg: String): Boolean {
        val m = msg.lowercase()
        return m.startsWith("network error") ||
                m.contains("unable to resolve host") ||
                m.contains("failed to connect") ||
                m.contains("timeout") ||
                m.contains("connection refused") ||
                m.contains("network is unreachable")
    }

    /* ---------- Optional reachability check (unused in runSmart now) ---------- */

    @Suppress("unused")
    private fun isServerReachable(): Boolean {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(2000, TimeUnit.MILLISECONDS)
                .callTimeout(3000, TimeUnit.MILLISECONDS)
                .build()

            val req = Request.Builder()
                .url(HEALTH_URL)
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun runViaLocalInterpreter(code: String, jumpToEnd: Boolean) {
        Thread {
            try {
                val interpreter = MiniInterpreter(code)
                interpreter.runAll()
                val steps = interpreter.steps

                requireActivity().runOnUiThread {
                    currentSteps = steps
                    if (currentSteps.isEmpty()) {
                        displayEmptyResults()
                    } else {
                        currentIndex = if (jumpToEnd) currentSteps.size - 1 else 0
                        showStep(currentIndex)
                    }
                    runButton.isEnabled = true
                    fastForwardButton.isEnabled = true
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    displayError("Local interpreter error: ${e.message}")
                    runButton.isEnabled = true
                    fastForwardButton.isEnabled = true
                }
            }
        }.start()
    }

    /* ---------- Save / UI helpers ---------- */

    private fun showSaveDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Save File")

        val input = EditText(requireContext())
        input.hint = "Enter filename (e.g., myscript.py)"
        builder.setView(input)

        builder.setPositiveButton("Save", null)
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }

        val dialog = builder.create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val rawName = input.text.toString().trim()

            if (rawName.isEmpty()) {
                input.error = "Filename cannot be empty"
            } else {
                val validName = if (rawName.endsWith(".py")) rawName else "$rawName.py"
                val file = File(requireContext().filesDir, validName)

                if (file.exists()) {
                    input.error = "File already exists! Choose a different name."
                } else {
                    saveFile(validName)
                    dialog.dismiss()
                }
            }
        }
    }

    private fun saveFile(filename: String) {
        val code = codeInput.text.toString()
        try {
            requireContext().openFileOutput(filename, Context.MODE_PRIVATE).use {
                it.write(code.toByteArray())
            }
            Toast.makeText(requireContext(), "Saved $filename", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error saving: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun displayEmptyResults() {
        highlightedCode.text = "(no steps produced)"
        varsView.text = "No execution steps generated"
        stepCounter.text = "Step 0 / 0"
    }

    private fun displayError(message: String) {
        highlightedCode.text = message
        varsView.text = ""
        stepCounter.text = "Error"
    }

    private fun toggleAutoplay() {
        autoPlay = !autoPlay
        if (autoPlay && currentIndex < currentSteps.size - 1) {
            handler.postDelayed(autoplayRunnable, 800)
        } else {
            handler.removeCallbacks(autoplayRunnable)
        }
    }

    private fun showStep(idx: Int) {
        if (idx < 0 || idx >= currentSteps.size) return
        val step = currentSteps[idx]
        highlightedCode.text = buildHighlightedCode(step.lineNo, step.codeLine)
        val info = StringBuilder()
        info.append("${step.desc}\n\n")
        if (step.varsSnapshot.isEmpty()) {
            info.append("Variables: (none)\n")
        } else {
            info.append("Variables:\n")
            step.varsSnapshot.forEach { (name, value) -> info.append("  $name = $value\n") }
        }
        if (step.visualizations.isNotEmpty()) {
            info.append("\nVisualizations:\n")
            step.visualizations.forEach { vis -> info.append("$vis\n\n") }
        }
        if (step.outputs.isNotEmpty()) {
            info.append("Output:\n")
            step.outputs.forEach { output -> info.append("  $output\n") }
        }
        varsView.text = info.toString()
        stepCounter.text = "Step ${idx + 1} / ${currentSteps.size}"
    }

    private fun buildHighlightedCode(activeLineNo: Int, activeLineText: String): String {
        val lines = codeInput.text.lines()
        val sb = StringBuilder()
        lines.forEachIndexed { index, line ->
            val lineNum = index + 1
            if (lineNum == activeLineNo) sb.append("▶ $lineNum: $line\n")
            else sb.append("  $lineNum: $line\n")
        }
        return sb.toString()
    }

    private fun setDefaultCode() {
        if (codeInput.text.isEmpty()) {
            codeInput.setText(
                """# Bubble Sort Visualization
arr = [64, 34, 25, 12, 22, 11, 90]
n = len(arr)
print("Initial array:")
print(arr)

for i in range(n):
    for j in range(0, n - i - 1):
        if arr[j] > arr[j + 1]:
            # Swap elements
            temp = arr[j]
            arr[j] = arr[j + 1]
            arr[j + 1] = temp
            print("Swapped positions")
            print(arr)

print("Sorted array:")
print(arr)""".trimIndent()
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(autoplayRunnable)
    }

    /* ---------- Remote execution ---------- */

    private fun runCodeOnServer(
        code: String,
        timeoutSec: Int = 5,
        jumpToEnd: Boolean = false,
        onSuccess: (List<StepEvent>) -> Unit,
        onError: (String) -> Unit
    ) {
        val client = OkHttpClient.Builder()
            .callTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .build()

        val json = JSONObject().apply {
            put("code", code)
            put("timeout", timeoutSec)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(SERVER_URL)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                requireActivity().runOnUiThread {
                    onError("Network error: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val text = response.body?.string()
                if (!response.isSuccessful || text == null) {
                    requireActivity().runOnUiThread {
                        onError("Server error: ${response.message}")
                    }
                    return
                }
                try {
                    val root = JSONObject(text)
                    val status = root.optString("status", "ok")
                    if (status != "ok") {
                        val msg = root.optString("message", "Execution failed")
                        val trace = root.optString("trace", "")
                        android.util.Log.e("REMOTE_ERROR", "msg=$msg\ntrace=$trace")
                        requireActivity().runOnUiThread { onError(msg) }
                        return
                    }
                    val stepsJson = root.optJSONArray("steps")
                    val parsed = ArrayList<StepEvent>()
                    if (stepsJson != null) {
                        for (i in 0 until stepsJson.length()) {
                            val s = stepsJson.getJSONObject(i)
                            val varsSnapshot = HashMap<String, String>()
                            val varsObj = s.optJSONObject("varsSnapshot")
                            if (varsObj != null) {
                                val keys = varsObj.keys()
                                while (keys.hasNext()) {
                                    val k = keys.next()
                                    varsSnapshot[k] = varsObj.optString(k, "null")
                                }
                            }
                            val outputs = ArrayList<String>()
                            val outArr = s.optJSONArray("outputs")
                            if (outArr != null) {
                                for (j in 0 until outArr.length()) outputs.add(outArr.optString(j))
                            }
                            val visuals = ArrayList<String>()
                            val visArr = s.optJSONArray("visualizations")
                            if (visArr != null) {
                                for (j in 0 until visArr.length()) visuals.add(visArr.optString(j))
                            }

                            val step = StepEvent(
                                lineNo = s.optInt("lineNo", 0),
                                codeLine = s.optString("codeLine", ""),
                                desc = s.optString("desc", ""),
                                varsSnapshot = varsSnapshot,
                                outputs = outputs,
                                visualizations = visuals
                            )
                            parsed.add(step)
                        }
                    }
                    requireActivity().runOnUiThread { onSuccess(parsed) }
                } catch (e: Exception) {
                    requireActivity().runOnUiThread { onError("Parse error: ${e.message}") }
                }
            }
        })
    }

    /* ---------- Smart runner (online + offline) ---------- */

    private fun runSmart(jumpToEnd: Boolean) {
        val code = codeInput.text.toString()

        runButton.isEnabled = false
        fastForwardButton.isEnabled = false

        runCodeOnServer(
            code = code,
            timeoutSec = 5,
            jumpToEnd = jumpToEnd,
            onSuccess = { steps ->
                currentSteps = steps
                if (currentSteps.isEmpty()) {
                    displayEmptyResults()
                } else {
                    currentIndex = if (jumpToEnd) currentSteps.size - 1 else 0
                    showStep(currentIndex)
                }
                runButton.isEnabled = true
                fastForwardButton.isEnabled = true
                Toast.makeText(requireContext(), "Executed on remote server", Toast.LENGTH_SHORT).show()
            },
            onError = { msg ->
                if (isNetworkError(msg)) {
                    // Offline or unreachable backend
                    if (isSimpleCode(code)) {
                        Toast.makeText(
                            requireContext(),
                            "No internet. Running in limited offline mode.",
                            Toast.LENGTH_SHORT
                        ).show()
                        runViaLocalInterpreter(code, jumpToEnd)
                    } else {
                        // Advanced Python – do NOT run locally, to avoid wrong results
                        displayError(
                            "This program uses Python features that require online execution.\n" +
                                    "Please connect to the internet and try again."
                        )
                        runButton.isEnabled = true
                        fastForwardButton.isEnabled = true
                    }
                } else {
                    // Real server/parse error
                    displayError(msg)
                    runButton.isEnabled = true
                    fastForwardButton.isEnabled = true
                }
            }
        )
    }
}
