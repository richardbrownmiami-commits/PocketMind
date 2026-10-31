package com.pocketmind.tools

/** Small bounded arithmetic parser used only after the model selects math.calculate. */
internal object SafeMath {
    fun evaluate(input: String): Double = Expression(input).run()

    private class Expression(private val source: String) {
        private var index = 0

        fun run(): Double {
            require(source.length <= 500) { "Expression is too long" }
            val result = add()
            skipWhitespace()
            require(index == source.length) { "Unexpected token" }
            require(result.isFinite()) { "Result is not finite" }
            return result
        }

        private fun add(): Double {
            var value = multiply()
            while (true) {
                skipWhitespace()
                if (index >= source.length || source[index] !in "+-") return value
                val operator = source[index++]
                val right = multiply()
                value = if (operator == '+') value + right else value - right
            }
        }

        private fun multiply(): Double {
            var value = unary()
            while (true) {
                skipWhitespace()
                if (index >= source.length || source[index] !in "*/%") return value
                val operator = source[index++]
                val right = unary()
                require(right != 0.0) { "Division by zero" }
                value = when (operator) {
                    '*' -> value * right
                    '/' -> value / right
                    else -> value % right
                }
            }
        }

        private fun unary(): Double {
            skipWhitespace()
            if (index < source.length && source[index] in "+-") {
                val operator = source[index++]
                val number = unary()
                return if (operator == '-') -number else number
            }
            if (index < source.length && source[index] == '(') {
                index++
                val number = add()
                skipWhitespace()
                require(index < source.length && source[index++] == ')') { "Missing closing parenthesis" }
                return number
            }
            val start = index
            while (index < source.length && (source[index].isDigit() || source[index] == '.')) index++
            require(index > start) { "Number expected" }
            return source.substring(start, index).toDouble().also { require(it.isFinite()) { "Number is not finite" } }
        }

        private fun skipWhitespace() { while (index < source.length && source[index].isWhitespace()) index++ }
    }
}
