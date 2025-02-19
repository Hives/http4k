package org.http4k.contract


import org.http4k.contract.PreFlightExtraction.Companion
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Method.OPTIONS
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Uri
import org.http4k.core.then
import org.http4k.filter.ServerFilters
import org.http4k.lens.LensFailure
import org.http4k.lens.PathLens
import org.http4k.routing.Router

class ContractRoute internal constructor(val method: Method,
                                         val spec: ContractRouteSpec,
                                         val meta: RouteMeta,
                                         internal val toHandler: (ExtractedParts) -> HttpHandler) : HttpHandler {
    val nonBodyParams = meta.requestParams.plus(spec.pathLenses).flatten()

    val tags = meta.tags.toSet().sortedBy { it.name }

    fun newRequest(baseUri: Uri) = Request(method, "").uri(baseUri.path(spec.describe(Root)))

    internal fun toRouter(contractRoot: PathSegments) = object : Router {
        override fun toString(): String = "${method.name}: ${spec.describe(contractRoot)}"

        override fun match(request: Request): HttpHandler? =
            if ((request.method == OPTIONS || request.method == method) && request.pathSegments().startsWith(spec.pathFn(contractRoot))) {
                try {
                    request.without(spec.pathFn(contractRoot))
                        .extract(spec.pathLenses.toList())
                        ?.let {
                            if (request.method == OPTIONS) {
                                { Response(OK) }
                            } else toHandler(it)
                        }
                } catch (e: LensFailure) {
                    null
                }
            } else null
    }

    fun describeFor(contractRoot: PathSegments) = spec.describe(contractRoot)

    /**
     * ContractRoutes are chiefly designed to operate within a contract {} block and not directly as an HttpHandler,
     * but this function exists to enable the testing of the ContractRoute logic outside of a wider contract context.
     * This means that certain behaviour is defaulted - chiefly the generation of NOT_FOUND and BAD_REQUEST responses.
     */
    override fun invoke(p1: Request): Response {
        val handler = toRouter(Root).match(p1)
            ?.let {
                meta.security.filter
                    .then(ServerFilters.CatchLensFailure { Response(BAD_REQUEST) })
                    .then(PreFlightExtractionFilter(meta, Companion.All))
                    .then(it)
            }
        return handler?.invoke(p1) ?: Response(NOT_FOUND)
    }

    override fun toString() = "${method.name}: ${spec.describe(Root)}"
}

internal class ExtractedParts(private val mapping: Map<PathLens<*>, *>) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(lens: PathLens<T>): T = mapping[lens] as T
}

private operator fun <T> PathSegments.invoke(index: Int, fn: (String) -> T): T? = toList().let { if (it.size > index) fn(it[index]) else null }

private fun PathSegments.extract(lenses: List<PathLens<*>>): ExtractedParts? =
    if (toList().size == lenses.size) ExtractedParts(lenses.mapIndexed { index, lens -> lens to this(index, lens::invoke) }.toMap()) else null

