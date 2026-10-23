package com.site.independent.parser

import org.springframework.expression.ExpressionParser
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext

object CustomSpringELParser {
    fun getDynamicValue(
        parameterNames: Array<String>,
        args: Array<Any>,
        key: String,
    ): Any {
        val parser: ExpressionParser = SpelExpressionParser()
        val context = StandardEvaluationContext()

        parameterNames.forEachIndexed { index, name ->
            context.setVariable(name, args[index])
        }

        return parser.parseExpression(key).getValue(context, Any::class.java)
            ?: error("SpEL 표현식 '$key' 의 결과가 null 입니다. 메서드 파라미터 이름과 표현식을 확인하세요.")
    }
}
