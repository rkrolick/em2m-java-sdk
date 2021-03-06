package io.em2m.simplex

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.em2m.simplex.model.*
import io.em2m.simplex.parser.ExprParser
import io.em2m.simplex.parser.SimplexModule
import io.em2m.simplex.std.StandardModule
import io.em2m.utils.coerce
import java.util.concurrent.ConcurrentHashMap

class Simplex() {

    private val keys = BasicKeyResolver()
    private val pipes = BasicPipeTransformResolver()
    private val conditions = BasicConditionResolver()
    private val execs = BasicExecResolver()

    private val cache = ConcurrentHashMap<String, Expr>()

    private val pathExprCache = ConcurrentHashMap<String, PathExpr>()

    val parser = ExprParser(keys, pipes)

    val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(SimplexModule(this))

    init {
        registerModule(StandardModule())
    }

    fun registerModule(module: Module): Simplex {
        module.configure(this)
        keys.delegate(module.keyResolver)
        pipes.delegate(module.pipeResolver)
        conditions.delegate(module.conditionResolver)
        execs.delegate(module.execResolver)
        return this
    }

    fun keys(delegate: KeyResolver): Simplex {
        keys.delegate(delegate)
        cache.clear()
        return this
    }

    fun pipes(delegate: PipeTransformResolver): Simplex {
        pipes.delegate(delegate)
        cache.clear()
        return this
    }

    fun conditions(delegate: ConditionResolver): Simplex {
        conditions.delegate(delegate)
        cache.clear()
        return this
    }

    fun execs(delegate: ExecResolver): Simplex {
        execs.delegate(delegate)
        cache.clear()
        return this
    }

    fun exec(exec: Exec, context: ExprContext): Any? {
        // todo - add cache
        val handler = execs.findHandler(exec.op)
        return if (handler != null) {
            val params = exec.params.mapValues { it.value?.call(context) }
            handler.call(context, exec.op, params)
        } else {
            throw RuntimeException("Need to handle not-found commands!")
            // ExecResult(null)
        }
    }

    fun getPath(path: String, context: Any?): Any? {
        val expr = pathExprCache.computeIfAbsent(path) { p -> PathExpr(p) }
        return expr.call(context)
    }

    fun compileCondition(op: String, key: String, value: List<String>): ConditionExpr {
        return getConditionExpr(op, key, value)
    }

    fun findConditionHandler(name: String): ConditionHandler? {
        return conditions.getCondition(name)
    }

    fun findExecHandler(op: String): ExecHandler? {
        return execs.findHandler(op)
    }

    private fun getConditionExpr(op: String, key: String, value: List<String>): SingleConditionExpr {
        val keyExpr = parser.parse("$" + "{" + key + "}")
        val values = ArrayExpr(value.map { parser.parse(it) })
        val handler = requireNotNull(conditions.getCondition(op))
        return SingleConditionExpr(op, handler, keyExpr, values)
    }


    private fun getKeyValue(key: String, context: ExprContext): Any? {
        val parsedKey = Key.parse(key)
        val contextKeys: KeyResolver? = context["keys"] as? KeyResolver
        val handler = contextKeys?.find(parsedKey) ?: keys.find(parsedKey)
        return handler?.call(parsedKey, context)
    }

    fun eval(expr: String, context: ExprContext): Any? {
        val parsed = cache.getOrPut(expr, { parser.parse(expr) })
        // safety measure until we implement an extensible caching API
        if (cache.size > 1000) {
            cache.clear()
        }
        return parsed.call(context)
    }

    companion object {
        val simplex = Simplex()
        val objectMapper = jacksonObjectMapper().registerModule(SimplexModule(simplex))
    }

}

fun Any?.evalPath(path: String): Any? {
    return Simplex.simplex.getPath(path, this)
}

inline fun <reified T : Any> Any?.evalPath(path: String, fallback: T?): T? {
    return Simplex.simplex.getPath(path, this).coerce(fallback)
}

