package com.kigtts.tools.agent

import kotlinx.serialization.json.*
import kotlin.math.*

/** Small least-squares fit, centered/scaled and solved with QR instead of unstable normal equations. */
internal fun fitAgentData(points: JsonArray, degree: Int): JsonObject {
    require(degree in 1..3 && points.size in (degree + 1)..2048) { "选择1至3阶，提供至少阶数+1且不超过2048个[x,y]点" }
    val values = points.map { item -> item.jsonArray.also { require(it.size == 2) }.map { it.jsonPrimitive.double.also { n -> require(n.isFinite() && abs(n) <= 1e12) } } }
    val x = values.map { it[0] }; val y = values.map { it[1] }
    val center = x.average(); val scale = x.maxOf { abs(it - center) }
    require(scale > max(1.0, abs(center)) * 1e-12) { "横坐标不能全部相同" }
    val normalized = x.map { (it - center) / scale }
    val q = Array(degree + 1) { column -> DoubleArray(points.size) { normalized[it].pow(column) } }
    val r = Array(degree + 1) { DoubleArray(degree + 1) }
    for (column in 0..degree) {
        for (previous in 0 until column) {
            r[previous][column] = q[column].indices.sumOf { q[column][it] * q[previous][it] }
            for (i in q[column].indices) q[column][i] -= r[previous][column] * q[previous][i]
        }
        r[column][column] = sqrt(q[column].sumOf { it * it })
        require(r[column][column] > 1e-9) { "数据中不同横坐标不足，请降低拟合阶数" }
        for (i in q[column].indices) q[column][i] /= r[column][column]
    }
    val coefficients = DoubleArray(degree + 1) { j -> y.indices.sumOf { y[it] * q[j][it] } }
    for (j in degree downTo 0) { for (k in j + 1..degree) coefficients[j] -= r[j][k] * coefficients[k]; coefficients[j] /= r[j][j] }
    val mean = y.average(); var error = 0.0; var variance = 0.0
    y.indices.forEach { i -> val predicted = coefficients.indices.sumOf { coefficients[it] * normalized[i].pow(it) }; error += (y[i] - predicted).pow(2); variance += (y[i] - mean).pow(2) }
    return buildJsonObject {
        put("points", points.size); put("degree", degree); put("xCenter", center); put("xScale", scale)
        put("coefficients", JsonArray(coefficients.map(::JsonPrimitive))); put("formula", "z=(x-xCenter)/xScale; y=coefficients[0]+coefficients[1]*z+...; coefficients in ascending power")
        put("rSquared", if (variance <= 1e-12) JsonNull else JsonPrimitive(1 - error / variance)); put("rmse", sqrt(error / points.size))
        put("xMin", x.min()); put("xMax", x.max()); put("method", "Polynomial least squares (QR); no extrapolation guarantee or causal inference")
    }
}
