package org.http4k.contract

import org.http4k.contract.security.Security
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.NoOp
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.core.UriTemplate
import org.http4k.core.then
import org.http4k.filter.ServerFilters.CatchLensFailure
import org.http4k.lens.LensFailure
import org.http4k.lens.Validator
import org.http4k.routing.RoutedRequest
import org.http4k.routing.RoutedResponse
import org.http4k.routing.RoutingHttpHandler

data class ContractRoutingHttpHandler(private val renderer: ContractRenderer,
                                      private val security: Security,
                                      private val descriptionPath: String,
                                      private val preFlightExtraction: PreFlightExtraction,
                                      private val routes: List<ContractRoute> = emptyList(),
                                      private val rootAsString: String = "",
                                      private val preSecurityFilter: Filter = Filter.NoOp,
                                      private val postSecurityFilter: Filter = Filter.NoOp,
                                      private val includeDescriptionRoute: Boolean = false
) : RoutingHttpHandler {
    private val contractRoot = PathSegments(rootAsString)

    fun withPostSecurityFilter(new: Filter) = copy(postSecurityFilter = postSecurityFilter.then(new))

    /**
     * NOTE: By default, filters for Contracts are applied *before* the Security filter. Use withPostSecurityFilter()
     * to achieve population of filters after security.
     */
    override fun withFilter(new: Filter) = copy(preSecurityFilter = new.then(preSecurityFilter))

    override fun withBasePath(new: String) = copy(rootAsString = new + rootAsString)

    private val notFound = preSecurityFilter.then(security.filter).then(postSecurityFilter).then { renderer.notFound() }

    private val handler: HttpHandler = { (match(it) ?: notFound).invoke(it) }

    override fun invoke(request: Request): Response = handler(request)

    private val descriptionRoute = ContractRouteSpec0({ PathSegments("$it$descriptionPath") }, RouteMeta(operationId = "description"))
        .let {
            val extra = listOfNotNull(if (includeDescriptionRoute) it bindContract GET to { Response(OK) } else null)
            it bindContract GET to { renderer.description(contractRoot, security, routes + extra) }
        }

    private val routers = routes
        .map {
            identify(it)
                .then(preSecurityFilter.then(security.filter).then(it.meta.security.filter).then(postSecurityFilter))
                .then(CatchLensFailure(renderer::badRequest))
                .then(PreFlightExtractionFilter(it.meta, preFlightExtraction)) to it.toRouter(contractRoot)
        } +
        (identify(descriptionRoute).then(preSecurityFilter).then(postSecurityFilter) to descriptionRoute.toRouter(contractRoot))

    override fun toString() = contractRoot.toString() + "\n" + routes.joinToString("\n") { it.toString() }

    override fun match(request: Request): HttpHandler? {
        val noMatch: HttpHandler? = null

        return if (request.isIn(contractRoot)) {
            routers.fold(noMatch) { memo, (routeFilter, router) ->
                memo ?: router.match(request)?.let { routeFilter.then(it) }
            }
        } else null
    }

    private fun identify(route: ContractRoute) =
        route.describeFor(contractRoot).let { routeIdentity ->
            Filter { next ->
                {
                    val xUriTemplate = UriTemplate.from(if (routeIdentity.isEmpty()) "/" else routeIdentity)
                    RoutedResponse(next(RoutedRequest(it, xUriTemplate)), xUriTemplate)
                }
            }
        }
}

internal class PreFlightExtractionFilter(meta: RouteMeta, preFlightExtraction: PreFlightExtraction) : Filter {
    private val preFlightChecks = (meta.preFlightExtraction ?: preFlightExtraction)(meta).toTypedArray()
    override fun invoke(next: HttpHandler): HttpHandler = {
        val failures = Validator.Strict(it, *preFlightChecks)
        if (failures.isEmpty()) next(it) else throw LensFailure(failures, target = it)
    }
}
