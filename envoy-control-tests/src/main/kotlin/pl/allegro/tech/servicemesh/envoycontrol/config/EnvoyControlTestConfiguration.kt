package pl.allegro.tech.servicemesh.envoycontrol.config

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.apache.http.conn.ssl.TrustAllStrategy
import org.apache.http.ssl.SSLContextBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ObjectAssert
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.springframework.boot.actuate.health.Status
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.EchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import java.security.KeyStore
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

sealed class EnvoyConfigFile(val filePath: String)
object AdsAllDependencies : EnvoyConfigFile("envoy/config_ads_all_dependencies.yaml")
object AdsCustomHealthCheck : EnvoyConfigFile("envoy/config_ads_custom_health_check.yaml")
object FaultyConfig : EnvoyConfigFile("envoy/bad_config.yaml")
object Ads : EnvoyConfigFile("envoy/config_ads.yaml")
object Envoy1AuthConfig : EnvoyConfigFile("envoy/envoy1_auth_config.yaml")
object Envoy2AuthConfig : EnvoyConfigFile("envoy/envoy2_auth_config.yaml")
object AdsWithDisabledEndpointPermissions : EnvoyConfigFile("envoy/config_ads_disabled_endpoint_permissions.yaml")
object AdsWithStaticListeners : EnvoyConfigFile("envoy/config_ads_static_listeners.yaml")
object AdsWithNoDependencies : EnvoyConfigFile("envoy/config_ads_no_dependencies.yaml")
object Xds : EnvoyConfigFile("envoy/config_xds.yaml")
object RandomConfigFile :
    EnvoyConfigFile(filePath = if (Random.nextBoolean()) Ads.filePath else Xds.filePath)

abstract class EnvoyControlTestConfiguration : BaseEnvoyTest() {
    companion object {
        private val logger by logger()
        private val defaultClient = OkHttpClient.Builder()
            // envoys default timeout is 15 seconds while OkHttp is 10
            .readTimeout(Duration.ofSeconds(20))
            .build()

        private val insecureClient = OkHttpClient.Builder()
                // envoys default timeout is 15 seconds while OkHttp is 10
                .sslSocketFactory(getInsecureSSLSocketFactory(), getInsecureTrustManager())
                .readTimeout(Duration.ofSeconds(20))
                .build()

        lateinit var envoyContainer1: EnvoyContainer
        lateinit var envoyContainer2: EnvoyContainer
        lateinit var localServiceContainer: EchoContainer
        lateinit var envoyControl1: EnvoyControlTestApp
        lateinit var envoyControl2: EnvoyControlTestApp
        var envoyControls: Int = 1
        var envoys: Int = 1

        // We use envoy version from master. This is 1.14.0-dev.
        const val defaultEnvoyImage = "envoyproxy/envoy-alpine-dev:6c2137468c25d167dbbe4719b0ecaf343bfb4233"

        @JvmStatic
        fun setup(
            envoyConfig: EnvoyConfigFile = RandomConfigFile,
            secondEnvoyConfig: EnvoyConfigFile = envoyConfig,
            envoyImage: String = defaultEnvoyImage,
            appFactoryForEc1: (Int) -> EnvoyControlTestApp = defaultAppFactory(),
            appFactoryForEc2: (Int) -> EnvoyControlTestApp = appFactoryForEc1,
            envoyControls: Int = 1,
            envoys: Int = 1,
            envoyConnectGrpcPort: Int? = null,
            envoyConnectGrpcPort2: Int? = null,
            ec1RegisterPort: Int? = null,
            ec2RegisterPort: Int? = null,
            instancesInSameDc: Boolean = false
        ) {
            assertThat(envoyControls == 1 || envoyControls == 2).isTrue()
            assertThat(envoys == 1 || envoys == 2).isTrue()

            localServiceContainer = EchoContainer().also { it.start() }

            Companion.envoyControls = envoyControls

            envoyControl1 = appFactoryForEc1(consulHttpPort).also { it.run() }

            if (envoyControls == 2) {
                envoyControl2 = appFactoryForEc2(consul2HttpPort).also { it.run() }
            }

            envoyContainer1 = createEnvoyContainer(
                instancesInSameDc,
                envoyConfig,
                envoyConnectGrpcPort,
                envoyConnectGrpcPort2,
                envoyImage = envoyImage
            )

            waitForEnvoyControlsHealthy()
            registerEnvoyControls(ec1RegisterPort, ec2RegisterPort, instancesInSameDc)
            try {
                envoyContainer1.start()
            } catch (e: Exception) {
                logger.error("Logs from failed container: ${envoyContainer1.logs}")
                throw e
            }

            if (envoys == 2) {
                envoyContainer2 = createEnvoyContainer(
                    true,
                    secondEnvoyConfig,
                    envoyConnectGrpcPort,
                    envoyConnectGrpcPort2,
                    echoContainer2.ipAddress(),
                    envoyImage = envoyImage
                )
                try {
                    envoyContainer2.start()
                } catch (e: Exception) {
                    logger.error("Logs from failed container: ${envoyContainer2.logs}")
                    throw e
                }
            }
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            envoyContainer1.stop()
            if (envoys == 2) {
                envoyContainer2.stop()
            }
            envoyControl1.stop()
            if (envoyControls == 2) {
                envoyControl2.stop()
            }
            localServiceContainer.stop()
        }

        private fun createEnvoyContainer(
            instancesInSameDc: Boolean,
            envoyConfig: EnvoyConfigFile,
            envoyConnectGrpcPort: Int?,
            envoyConnectGrpcPort2: Int?,
            localServiceIp: String = localServiceContainer.ipAddress(),
            envoyImage: String = defaultEnvoyImage
        ): EnvoyContainer {
            return if (envoyControls == 2 && instancesInSameDc) {
                EnvoyContainer(
                    envoyConfig.filePath,
                    localServiceIp,
                    envoyConnectGrpcPort ?: envoyControl1.grpcPort,
                    envoyConnectGrpcPort2 ?: envoyControl2.grpcPort,
                    image = envoyImage
                ).withNetwork(network)
            } else {
                EnvoyContainer(
                    envoyConfig.filePath,
                    localServiceIp,
                    envoyConnectGrpcPort ?: envoyControl1.grpcPort,
                    image = envoyImage
                ).withNetwork(network)
            }
        }

        fun createEnvoyContainerWithFaultyConfig(): EnvoyContainer {
            return createEnvoyContainer(true, FaultyConfig, null, null)
                .withStartupTimeout(Duration.ofSeconds(10))
        }

        fun createEnvoyContainerWithEcho2San(): EnvoyContainer {
            return EnvoyContainer(
                    Envoy1AuthConfig.filePath,
                    localServiceContainer.ipAddress(),
                    envoyControl1.grpcPort,
                    image = defaultEnvoyImage,
                    certificate = "testcontainers/ssl/fullchain_echo2.pem"
            ).withNetwork(network)
        }

        fun registerEnvoyControls(
            ec1RegisterPort: Int?,
            ec2RegisterPort: Int?,
            instancesInSameDc: Boolean
        ) {
            registerService(
                "1",
                envoyControl1.appName,
                "localhost",
                ec1RegisterPort ?: envoyControl1.appPort,
                consulOperationsInFirstDc
            )
            if (envoyControls == 2) {
                registerService(
                    "2",
                    envoyControl2.appName,
                    "localhost",
                    ec2RegisterPort ?: envoyControl2.appPort,
                    if (instancesInSameDc) consulOperationsInFirstDc else consulOperationsInSecondDc
                )
            }
        }

        private fun waitForEnvoyControlsHealthy() {
            await().atMost(30, TimeUnit.SECONDS).untilAsserted {
                assertThat(envoyControl1.isHealthy()).isTrue()
                if (envoyControls == 2) {
                    assertThat(envoyControl2.isHealthy()).isTrue()
                }
            }
        }

        fun callEnvoyIngress(path: String): Response =
            call("", address = envoyContainer1.ingressListenerUrl(), pathAndQuery = path)

        fun callIngressRoot(address: String = envoyContainer1.ingressListenerUrl()): Response =
            call("", address)

        fun callEcho(address: String = envoyContainer1.egressListenerUrl()): Response =
            call("echo", address)

        fun callDomain(domain: String, address: String = envoyContainer1.egressListenerUrl()): Response =
            call(domain, address)

        fun callService(
            service: String,
            address: String = envoyContainer1.egressListenerUrl(),
            headers: Map<String, String> = mapOf(),
            pathAndQuery: String = ""
        ): Response = call(service, address, headers, pathAndQuery)

        private fun getInsecureSSLSocketFactory(): SSLSocketFactory {
            val builder = SSLContextBuilder()
            builder.loadTrustMaterial(null, TrustAllStrategy())
            return builder.build().socketFactory
        }

        private fun getInsecureTrustManager(): X509TrustManager {
            val trustManagerFactory: TrustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm())
            trustManagerFactory.init(null as KeyStore?)
            val trustManagers = trustManagerFactory.trustManagers
            return trustManagers[0] as X509TrustManager
        }

        fun callServiceInsecure(
            service: String,
            address: String = envoyContainer1.egressListenerUrl(),
            headers: Map<String, String> = mapOf(),
            pathAndQuery: String = ""
        ): Response = call(service, address, headers, pathAndQuery, insecureClient)

        private fun call(
            host: String,
            address: String = envoyContainer1.egressListenerUrl(),
            headers: Map<String, String> = mapOf(),
            pathAndQuery: String = "",
            client: OkHttpClient = defaultClient
        ): Response {
            val request = client.newCall(
                Request.Builder()
                    .get()
                    .header("Host", host)
                    .apply {
                        headers.forEach { name, value -> header(name, value) }
                    }
                    .url(HttpUrl.get(address).newBuilder(pathAndQuery)!!.build())
                    .build()
            )

            return request.execute()
        }

        fun callServiceWithOriginalDst(originalDstUrl: String, envoyUrl: String): Response =
            defaultClient.newCall(
                Request.Builder()
                    .get()
                    .header("Host", "envoy-original-destination")
                    .header("x-envoy-original-dst-host", originalDstUrl)
                    .url(envoyUrl)
                    .build()
            )
                .execute()

        fun callLocalService(
            endpoint: String,
            headers: Headers,
            envoyContainer: EnvoyContainer = envoyContainer1
        ): Response =
            defaultClient.newCall(
                Request.Builder()
                    .get()
                    .headers(headers)
                    .url(envoyContainer.ingressListenerUrl() + endpoint)
                    .build()
            )
                .execute()

        fun callPostLocalService(
            endpoint: String,
            headers: Headers,
            body: RequestBody,
            envoyContainer: EnvoyContainer = envoyContainer1
        ): Response =
            defaultClient.newCall(
                Request.Builder()
                    .post(body)
                    .headers(headers)
                    .url(envoyContainer.ingressListenerUrl() + endpoint)
                    .build()
            )
                .execute()

        private fun waitForConsulSync() {
            await().atMost(defaultDuration).until { !callEcho().use { it.isSuccessful } }
        }

        private fun defaultAppFactory(): (Int) -> EnvoyControlRunnerTestApp {
            return { consulPort ->
                EnvoyControlRunnerTestApp(
                    consulPort = consulPort
                )
            }
        }
    }

    data class ResponseWithBody(val response: Response, val body: String) {
        fun isFrom(echoContainer: EchoContainer) = body.contains(echoContainer.response)
        fun isOk() = response.isSuccessful
    }

    class CallStats(private val containers: List<EchoContainer>) {
        var failedHits: Int = 0
        var totalHits: Int = 0

        private var containerHits: MutableMap<String, Int> = containers.associate { it.containerId to 0 }.toMutableMap()

        fun hits(container: EchoContainer) = containerHits[container.containerId] ?: 0

        fun addResponse(response: ResponseWithBody) {
            containers.firstOrNull { response.isFrom(it) }
                ?.let { containerHits.compute(it.containerId) { _, i -> i?.inc() } }
            if (!response.isOk()) failedHits++
            totalHits++
        }
    }

    protected fun callServiceRepeatedly(
        service: String,
        stats: CallStats,
        minRepeat: Int = 1,
        maxRepeat: Int = 100,
        repeatUntil: (ResponseWithBody) -> Boolean = { false },
        headers: Map<String, String> = mapOf(),
        pathAndQuery: String = "",
        assertNoErrors: Boolean = true
    ): CallStats {
        var conditionFulfilled = false
        (1..maxRepeat).asSequence()
            .map { i ->
                callService(service = service, headers = headers, pathAndQuery = pathAndQuery).also {
                    if (assertNoErrors) assertThat(it).isOk().describedAs("Error response at attempt $i: \n$it")
                }
            }
            .map { ResponseWithBody(it, it.body()?.string() ?: "") }
            .onEach { conditionFulfilled = conditionFulfilled || repeatUntil(it) }
            .withIndex()
            .takeWhile { (i, _) -> i < minRepeat || !conditionFulfilled }
            .map { it.value }
            .forEach { stats.addResponse(it) }
        return stats
    }

    private fun waitForEchoServices(instances: Int) {
        untilAsserted {
            assertThat(envoyContainer1.admin().numOfEndpoints(clusterName = "echo")).isEqualTo(instances)
        }
    }

    fun checkTrafficRoutedToSecondInstance(id: String) {
        // given
        // we first register a new instance and then remove other to maintain cluster presence in Envoy
        registerService(name = "echo", container = echoContainer2)
        waitForEchoServices(instances = 2)

        deregisterService(id)
        waitForEchoServices(instances = 1)

        untilAsserted {
            // when
            val response = callEcho()

            // then
            assertThat(response).isOk().isFrom(echoContainer2)
        }
    }

    fun waitUntilEchoCalledThroughEnvoyResponds(target: EchoContainer) {
        untilAsserted {
            // when
            val response = callEcho()

            // then
            assertThat(response).isOk().isFrom(target)
        }
    }

    /**
     * We have to retrieve the bean manually instead of @Autowired because the app is created in manual way
     * instead of using the JUnit Spring Extension
     */
    inline fun <reified T> bean(): T = envoyControl1.bean(T::class.java)

    fun waitForReadyServices(vararg serviceNames: String) {
        serviceNames.forEach {
            untilAsserted {
                callService(it).also {
                    assertThat(it).isOk()
                }
            }
        }
    }

    fun <T> untilAsserted(wait: org.awaitility.Duration = defaultDuration, fn: () -> (T)): T {
        var lastResult: T? = null
        await().atMost(wait).untilAsserted({ lastResult = fn() })
        assertThat(lastResult).isNotNull
        return lastResult!!
    }

    fun ObjectAssert<Response>.isOk(): ObjectAssert<Response> {
        matches { it.isSuccessful }
        return this
    }

    fun ObjectAssert<Response>.isFrom(echoContainer: EchoContainer): ObjectAssert<Response> {
        matches {
            it.body()?.use { it.string().contains(echoContainer.response) } ?: false
        }
        return this
    }

    fun ObjectAssert<Response>.hasHostHeaderWithValue(overriddenHostHeader: String): ObjectAssert<Response> {
        matches({
            it.body()?.use { it.string().contains("\"host\": \"$overriddenHostHeader\"") } ?: false
        }, "Header Host should be overridden with value: $overriddenHostHeader")
        return this
    }

    fun ObjectAssert<Response>.isUnreachable(): ObjectAssert<Response> {
        matches({
            it.body()?.close()
            it.code() == 503 || it.code() == 504
        }, "is unreachable")
        return this
    }

    fun ObjectAssert<Response>.isForbidden(): ObjectAssert<Response> {
        matches({
            it.body()?.close()
            it.code() == 403
        }, "is forbidden")
        return this
    }

    fun ObjectAssert<Response>.hasLocationHeaderFrom(serviceName: String): ObjectAssert<Response> {
        matches { it.headers("location").contains("http://$serviceName/") }
        return this
    }

    fun ObjectAssert<Response>.hasXEnvoyUpstreamRemoteAddressFrom(echoContainer: EchoContainer): ObjectAssert<Response> {
        matches { it.headers("x-envoy-upstream-remote-address").contains(echoContainer.address()) }
        return this
    }

    fun ObjectAssert<Health>.isStatusHealthy(): ObjectAssert<Health> {
        matches { it.status == Status.UP }
        return this
    }

    fun ObjectAssert<Health>.hasEnvoyControlCheckPassed(): ObjectAssert<Health> {
        matches { it.details.get("envoyControl")?.status == Status.UP }
        return this
    }

    fun ObjectAssert<ServicesState>.hasServiceStateChanged(): ObjectAssert<ServicesState> {
        matches { it.serviceNames().isNotEmpty() }
        return this
    }

    @AfterEach
    fun cleanupTest() {
        deregisterAllServices()
        envoyContainer1.admin().resetCounters()
        if (envoys == 2) {
            envoyContainer2.admin().resetCounters()
        }
        waitForConsulSync()
    }
}
