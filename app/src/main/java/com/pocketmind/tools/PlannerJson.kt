package com.pocketmind.tools

/** Strict, dependency-free parser for the planner's deliberately tiny JSON contract. */
object PlannerJson {
    fun parse(json: String): List<PlanStep> {
        return Parser(json).parse()
    }
    private class Parser(private val s: String) {
        private var i = 0
        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun take(c: Char) { ws(); require(i < s.length && s[i++] == c) { "Expected '$c'" } }
        private fun str(): String { ws(); take('"'); val b = StringBuilder(); while (i < s.length) { val c=s[i++]; if(c=='"') return b.toString(); if(c=='\\') { require(i<s.length); when(val e=s[i++]) { '"','\\','/' -> b.append(e); 'b'->b.append('\b'); 'f'->b.append('\u000c'); 'n'->b.append('\n'); 'r'->b.append('\r'); 't'->b.append('\t'); 'u'->{require(i+4<=s.length); b.append(s.substring(i,i+4).toInt(16).toChar()); i+=4}; else->error("Bad escape") } } else { require(c.code>=0x20); b.append(c) } }; error("Unterminated string") }
        private fun value(): Any? { ws(); require(i<s.length); return when(s[i]) { '"' -> str(); 't'->{literal("true");true}; 'f'->{literal("false");false}; 'n'->{literal("null");null}; '{','[' -> error("Nested structures are not supported in arguments"); else -> number() } }
        private fun literal(x:String) { require(s.startsWith(x,i)); i+=x.length }
        private fun number(): Number { val start=i; if(s[i]=='-')i++; require(i<s.length && s[i].isDigit()); if(s[i]=='0')i++ else while(i<s.length&&s[i].isDigit())i++; var decimal=false; if(i<s.length&&s[i]=='.'){decimal=true;i++;require(i<s.length&&s[i].isDigit());while(i<s.length&&s[i].isDigit())i++}; if(i<s.length&&(s[i]=='e'||s[i]=='E')){decimal=true;i++;if(i<s.length&&(s[i]=='+'||s[i]=='-'))i++;require(i<s.length&&s[i].isDigit());while(i<s.length&&s[i].isDigit())i++}; val raw=s.substring(start,i); return if(decimal) raw.toDouble() else raw.toLong() }
        private fun key(expected:String) { require(str()==expected) { "Expected key $expected" } }
        fun parse(): List<PlanStep> { take('{'); key("steps"); take(':'); take('['); val steps=mutableListOf<PlanStep>(); ws(); if(i<s.length&&s[i]!=']') while(true) { take('{'); key("tool");take(':');val tool=str();take(',');key("args");take(':');take('{');val args=linkedMapOf<String,Any?>();ws();if(i<s.length&&s[i]!='}')while(true){val k=str();take(':');require(!args.containsKey(k)){"Duplicate arg"};args[k]=value();ws();if(i<s.length&&s[i]==','){i++;continue};break};take('}');take('}');steps.add(PlanStep(tool,args));ws();if(i<s.length&&s[i]==','){i++;continue};break };take(']');take('}');ws();require(i==s.length){"Trailing data"};require(steps.size<=8){"Too many steps"};return steps }
    }
}
