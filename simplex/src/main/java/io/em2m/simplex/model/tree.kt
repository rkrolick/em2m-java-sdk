package io.em2m.simplex.model

import io.em2m.utils.coerce

interface TreeExpr : Expr

class ArrayExpr(val values: List<Expr>) : TreeExpr {

    override fun call(context: ExprContext): List<Any?> {
        return values.map { it.call(context) }.toList()
    }
}

class FieldExpr(val field: String, val value: Expr) : TreeExpr {

    override fun call(context: ExprContext): Pair<String, Any?> {
        return field to value.call(context)
    }
}

class ObjectExpr(val fields: List<FieldExpr>) : TreeExpr {

    private val fieldMap = fields.associateBy { it.field }

    override fun call(context: ExprContext): Any? {
        val skip = !(fieldMap["@if"]?.value?.call(context)?.coerce(true) ?: true)
        return when {
            skip -> null
            fieldMap.containsKey("@repeat") -> processRepeat(context)
            fieldMap.containsKey("@value") -> processValue(context)
            else -> processFields(context)
        }
    }

    private fun processValue(context: ExprContext): Any? {
        return fieldMap["@value"]?.value?.call(context)
    }

    class RepeatState(val size: Int) {
        var item: Any? = null
        var index: Int = 0
        val even: Boolean
            get() = (index % 2 == 0)
        val odd: Boolean
            get() = (index % 2 == 1)
        val first: Boolean
            get() = (index == 0)
        val last: Boolean
            get() = (index == size - 1)
    }

    private fun processRepeat(context: ExprContext): List<Any?> {
        val items: List<Any?> = (fieldMap["@repeat"]?.value)?.call(context).coerce() ?: emptyList()
        val valueExpr = fieldMap["@value"]?.value
        return if (items.isNotEmpty()) {
            val state = RepeatState(items.size)
            val repeatContext = context.plus("repeat" to state)
            items.mapIndexed { index, item ->
                state.item = item
                state.index = index
                if (valueExpr != null) {
                    valueExpr.call(repeatContext)
                } else {
                    processFields(repeatContext)
                }
            }
        } else emptyList()
    }

    private fun processFields(context: ExprContext): Map<String, Any?> {

        return fields.filter {
            // don't render annotations
            !it.field.startsWith("@") || it.field.startsWith("@container")
        }.flatMap { field ->
            val key = field.field
            val value = field.value.call(context)
            // skip objects that return null
            if (field.value is ObjectExpr && value == null) {
                listOf(null)
            } else if (field.value is ObjectExpr && field.field.startsWith("@container")) {
                val map: Map<String, Any?> = value.coerce() ?: emptyMap()
                map.toList()
            } else {
                listOf(key to value)
            }
        }.filterNotNull().associate { it }
    }

}
