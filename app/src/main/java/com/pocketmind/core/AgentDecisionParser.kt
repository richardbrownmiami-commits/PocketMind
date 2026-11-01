package com.pocketmind.core

import com.pocketmind.tools.ToolInvocation
import com.pocketmind.tools.ToolRegistry
import java.math.BigDecimal

sealed interface AgentDecision {
    data class Final(val text: String) : AgentDecision
    data class Call(val invocation: ToolInvocation) : AgentDecision
}

/** Strict JSON decoder for one model decision; nested argument values are intentionally rejected. */
internal object AgentDecisionParser {
    fun parse(source: String): AgentDecision {
        val root = JsonReader(source).readDecisionObject()
        return when (root["kind"]) {
            "final" -> {
                require(root.keys == setOf("kind", "text")) { "Final decision has unexpected fields" }
                val text = root["text"] as? String ?: error("Final text must be a string")
                require(text.isNotBlank()) { "Final text is empty" }
                AgentDecision.Final(text.take(4000))
            }
            "call" -> {
                require(root.keys == setOf("kind", "tool", "args")) { "Tool call has unexpected fields" }
                val tool = root["tool"] as? String ?: error("Tool id must be a string")
                require(tool.matches(Regex("[A-Za-z0-9._/-]{1,80}"))) { "Invalid tool id" }
                @Suppress("UNCHECKED_CAST")
                val args = root["args"] as? Map<String, Any?> ?: error("Tool arguments must be an object")
                AgentDecision.Call(ToolInvocation(tool, args))
            }
            else -> error("Decision kind must be final or call")
        }
    }

    private class JsonReader(private val source: String) {
        private var index = 0

        fun readDecisionObject(): Map<String, Any?> {
            skipWhitespace()
            expect('{')
            val values = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek('}')) error("Decision object is empty")
            while (true) {
                val key = stringValue()
                require(!values.containsKey(key)) { "Duplicate JSON key" }
                expect(':')
                val value = if (key == "args") flatObject() else flatValue()
                values[key] = value
                skipWhitespace()
                if (peek('}')) {
                    index++
                    break
                }
                expect(',')
            }
            skipWhitespace()
            require(index == source.length) { "Trailing data after decision" }
            return values
        }

        private fun flatObject(): Map<String, Any?> {
            expect('{')
            val values = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek('}')) {
                index++
                return values
            }
            while (true) {
                val key = stringValue()
                require(!values.containsKey(key)) { "Duplicate argument" }
                expect(':')
                values[key] = flatValue()
                skipWhitespace()
                if (peek('}')) {
                    index++
                    return values
                }
                expect(',')
            }
        }

        private fun flatValue(): Any? {
            skipWhitespace()
            require(index < source.length) { "Missing JSON value" }
            return when (source[index]) {
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '{', '[' -> error("Nested argument structures are unsupported")
                else -> numberValue()
            }
        }

        private fun stringValue(): String {
            skipWhitespace()
            expect('"')
            val out = StringBuilder()
            while (index < source.length) {
                val char = source[index++]
                when {
                    char == '"' -> return out.toString()
                    char == '\\' -> {
                        require(index < source.length) { "Incomplete JSON escape" }
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(index + 4 <= source.length) { "Incomplete Unicode escape" }
                                val code = source.substring(index, index + 4).toIntOrNull(16)
                                    ?: error("Invalid Unicode escape")
                                out.append(code.toChar())
                                index += 4
                            }
                            else -> error("Invalid JSON escape")
                        }
                    }
                    char.code < 0x20 -> error("Unescaped control character in string")
                    else -> out.append(char)
                }
            }
            error("Unterminated JSON string")
        }

        private fun numberValue(): Number {
            val start = index
            if (peek('-')) index++
            require(index < source.length && source[index].isDigit()) { "Invalid JSON number" }
            if (peek('0')) index++ else while (index < source.length && source[index].isDigit()) index++
            var decimal = false
            if (peek('.')) {
                decimal = true
                index++
                require(index < source.length && source[index].isDigit()) { "Invalid decimal number" }
                while (index < source.length && source[index].isDigit()) index++
            }
            if (peek('e') || peek('E')) {
                decimal = true
                index++
                if (peek('+') || peek('-')) index++
                require(index < source.length && source[index].isDigit()) { "Invalid exponent" }
                while (index < source.length && source[index].isDigit()) index++
            }
            val number = source.substring(start, index)
            if (!decimal) return number.toLongOrNull() ?: error("Integer is out of range")
            return BigDecimal(number).toDouble().also { require(it.isFinite()) { "Number is out of range" } }
        }

        private fun <T> literal(value: String, result: T): T {
            require(source.startsWith(value, index)) { "Invalid JSON literal" }
            index += value.length
            return result
        }

        private fun expect(char: Char) {
            skipWhitespace()
            require(index < source.length && source[index] == char) { "Expected '$char'" }
            index++
        }

        private fun peek(char: Char): Boolean = index < source.length && source[index] == char
        private fun skipWhitespace() { while (index < source.length && source[index].isWhitespace()) index++ }
    }
}

/** llama.cpp GBNF constrains output to the same one-decision protocol; parsing remains mandatory. */
internal object AgentDecisionGrammar {
    fun build(registry: ToolRegistry, allowToolCalls: Boolean = true): String {
        val tools = if (allowToolCalls) registry.all else emptyList()
        return buildString {
            appendLine(if (tools.isEmpty()) "root ::= final" else "root ::= final | call")
            appendLine("final ::= \"{\\\"kind\\\":\\\"final\\\",\\\"text\\\":\" nonempty-string \"}\"")
            appendLine("string ::= \"\\\"\" json-char* \"\\\"\"")
            appendLine("nonempty-string ::= \"\\\"\" json-char json-char* \"\\\"\"")
            appendLine("json-char ::= [^\"\\\\\\x00-\\x1F] | \"\\\\\" [\"\\\\/bfnrt] | \"\\\\u\" [0-9a-fA-F]{4}")
            appendLine("integer ::= \"-\"? (\"0\" | [1-9] [0-9]*)")
            appendLine("number ::= \"-\"? (\"0\" | [1-9] [0-9]*) (\".\" [0-9]+)? ([eE] [+-]? [0-9]+)?")

            if (tools.isNotEmpty()) {
                appendLine("call ::= ${tools.indices.joinToString(" | ") { "call-$it" }}")
                tools.forEachIndexed { toolIndex, spec ->
                    val argsRule = "args-$toolIndex"
                    appendLine("call-$toolIndex ::= ${grammarLiteral("{\"kind\":\"call\",\"tool\":\"${spec.id}\",\"args\":")} $argsRule ${grammarLiteral("}")}")
                    spec.args.forEachIndexed { argIndex, arg ->
                        val rule = argumentRule(toolIndex, argIndex)
                        appendLine("$rule ::= ${argumentValueRule(arg)}")
                    }
                    val alternatives = argumentVariants(spec).map { args ->
                        if (args.isEmpty()) grammarLiteral("{}") else buildString {
                            append(grammarLiteral("{"))
                            args.forEachIndexed { argIndex, arg ->
                                append(' ')
                                if (argIndex > 0) append(grammarLiteral(",")).append(' ')
                                append(grammarLiteral("\"${arg.name}\":"))
                                append(' ').append(argumentRule(toolIndex, spec.args.indexOf(arg)))
                            }
                            append(' ').append(grammarLiteral("}"))
                        }
                    }
                    appendLine("$argsRule ::= ${alternatives.joinToString(" | ")}")
                }
            }
        }.trimEnd()
    }

    private fun argumentVariants(spec: com.pocketmind.tools.ToolSpec): List<List<com.pocketmind.tools.ArgSpec>> {
        val required = spec.args.filter { it.required }
        val optional = spec.args.filterNot { it.required }
        require(optional.size <= 12) { "Tool schema has too many optional arguments" }
        return (0 until (1 shl optional.size)).map { mask ->
            val chosen = required + optional.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            chosen
        }
    }

    private fun argumentRule(toolIndex: Int, argIndex: Int): String = "arg-$toolIndex-$argIndex"

    private fun argumentValueRule(arg: com.pocketmind.tools.ArgSpec): String = when (arg.type) {
        com.pocketmind.tools.ArgType.STRING -> "string"
        com.pocketmind.tools.ArgType.NUMBER -> "number"
        com.pocketmind.tools.ArgType.BOOLEAN -> "(\"true\" | \"false\")"
        com.pocketmind.tools.ArgType.INTEGER -> "integer"
    }

    private fun grammarLiteral(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
