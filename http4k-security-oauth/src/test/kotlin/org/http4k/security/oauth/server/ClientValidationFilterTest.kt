package org.http4k.security.oauth.server

import com.natpryce.hamkrest.and
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import kotlinx.coroutines.runBlocking
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Uri
import org.http4k.core.then
import org.http4k.format.Jackson
import org.http4k.hamkrest.hasBody
import org.http4k.hamkrest.hasStatus
import org.http4k.security.ResponseType.Code
import org.junit.jupiter.api.Test

internal class ClientValidationFilterTest {
    private val documentationUri = "SomeUri"
    private val validClientId = ClientId("a-client")
    private val validRedirectUri = Uri.of("https://a-redirect-uri")

    private val loginPage = HttpHandler { _: Request -> Response(OK).body("login page") }
    private val isLoginPage = hasStatus(OK) and hasBody("login page")
    private val json = Jackson

    private val filter =
        ClientValidationFilter(HardcodedClientValidator(validClientId, validRedirectUri), ErrorRenderer(json, documentationUri), AuthRequestFromQueryParameters)
            .then(loginPage)


    @Test
    fun `allow accessing the login page`() = runBlocking {
        val response = filter(Request(GET, "/auth")
            .query("response_type", Code.queryParameterValue)
            .query("client_id", validClientId.value)
            .query("redirect_uri", validRedirectUri.toString())
        )
        assertThat(response, isLoginPage)
    }

    @Test
    fun `validates presence of client_id`() = runBlocking {
        val response = filter(Request(GET, "/auth")
            .query("response_type", Code.queryParameterValue)
            .query("redirect_uri", validRedirectUri.toString())
        )
        assertThat(response, hasStatus(BAD_REQUEST))
        assertThat(response.bodyString(), equalTo("{\"error\":\"invalid_request\",\"error_description\":\"query 'client_id' is required\",\"error_uri\":\"SomeUri\"}"))
    }

    @Test
    fun `validates presence of redirect_uri`() = runBlocking {
        val response = filter(Request(GET, "/auth")
            .query("response_type", Code.queryParameterValue)
            .query("client_id", validClientId.value)
        )
        assertThat(response, hasStatus(BAD_REQUEST))
        assertThat(response.bodyString(), equalTo("{\"error\":\"invalid_request\",\"error_description\":\"query 'redirect_uri' is required\",\"error_uri\":\"SomeUri\"}"))
    }

    @Test
    fun `validates client_id value`() = runBlocking {
        val response = filter(Request(GET, "/auth")
            .query("response_type", Code.queryParameterValue)
            .query("client_id", "invalid-client")
            .query("redirect_uri", validRedirectUri.toString())
        )
        assertThat(response, hasStatus(BAD_REQUEST))
        assertThat(response.bodyString(), equalTo("{\"error\":\"invalid_client\",\"error_description\":\"The specified client id is invalid\",\"error_uri\":\"SomeUri\"}"))
    }

    @Test
    fun `validates redirect_uri value`() = runBlocking {
        val response = filter(Request(GET, "/auth")
            .query("response_type", Code.queryParameterValue)
            .query("client_id", validClientId.value)
            .query("redirect_uri", "invalid-redirect")
        )
        assertThat(response, hasStatus(BAD_REQUEST))
        assertThat(response.bodyString(), equalTo("{\"error\":\"invalid_client\",\"error_description\":\"The specified redirect uri is not registered\",\"error_uri\":\"SomeUri\"}"))
    }

    @Test
    fun `validates response_type`() = runBlocking {
        val response = filter(Request(GET, "/auth")
            .query("response_type", "invalid")
            .query("client_id", validClientId.value)
            .query("redirect_uri", validRedirectUri.toString())
        )
        assertThat(response, hasStatus(BAD_REQUEST))
        assertThat(response.bodyString(), equalTo("{\"error\":\"unsupported_response_type\",\"error_description\":\"The specified response_type 'invalid' is not supported\",\"error_uri\":\"SomeUri\"}"))
    }
}

