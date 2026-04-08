package com.chatbot.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

@Component
public class CalculatorTool {

    @Tool(description = "수식을 계산합니다. 사칙연산(+, -, *, /), 괄호, 거듭제곱(**) 등을 지원합니다. 예: (3 + 5) * 2, 100 / 4")
    public String calculate(String expression) {
        try {
            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("JavaScript");
            if (engine == null) {
                return simpleCalculate(expression);
            }
            Object result = engine.eval(expression);
            return expression + " = " + result;
        } catch (Exception e) {
            return "계산할 수 없는 수식입니다: " + expression;
        }
    }

    // JavaScript 엔진이 없는 경우 대비 간단한 계산기
    private String simpleCalculate(String expression) {
        try {
            expression = expression.trim().replaceAll("\\s+", "");
            double result = eval(expression);
            if (result == Math.floor(result)) {
                return expression + " = " + (long) result;
            }
            return expression + " = " + result;
        } catch (Exception e) {
            return "계산할 수 없는 수식입니다: " + expression;
        }
    }

    private double eval(String expr) {
        return new Object() {
            int pos = -1, ch;

            void nextChar() { ch = (++pos < expr.length()) ? expr.charAt(pos) : -1; }
            boolean eat(int charToEat) {
                while (ch == ' ') nextChar();
                if (ch == charToEat) { nextChar(); return true; }
                return false;
            }
            double parse() { nextChar(); double x = parseExpr(); if (pos < expr.length()) throw new RuntimeException(); return x; }
            double parseExpr() {
                double x = parseTerm();
                while (true) {
                    if (eat('+')) x += parseTerm();
                    else if (eat('-')) x -= parseTerm();
                    else return x;
                }
            }
            double parseTerm() {
                double x = parseFactor();
                while (true) {
                    if (eat('*')) x *= parseFactor();
                    else if (eat('/')) x /= parseFactor();
                    else return x;
                }
            }
            double parseFactor() {
                if (eat('+')) return parseFactor();
                if (eat('-')) return -parseFactor();
                double x;
                int startPos = pos;
                if (eat('(')) { x = parseExpr(); eat(')'); }
                else if ((ch >= '0' && ch <= '9') || ch == '.') {
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    x = Double.parseDouble(expr.substring(startPos + 1, pos));
                } else throw new RuntimeException();
                return x;
            }
        }.parse();
    }
}
