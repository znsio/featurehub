package io.featurehub.jersey

import cd.connect.app.config.ConfigKey
import cd.connect.app.config.DeclaredConfigResolver
import cd.connect.lifecycle.ApplicationLifecycleManager
import cd.connect.lifecycle.LifecycleStatus
import cd.connect.lifecycle.LifecycleTransition
import io.featurehub.health.CommonFeatureHubFeatures
import io.featurehub.lifecycle.ExecutorPoolDrainageSource
import io.featurehub.utils.FallbackPropertyConfig
import org.glassfish.grizzly.http.server.HttpHandlerRegistration
import org.glassfish.grizzly.http.server.HttpServer
import org.glassfish.grizzly.http.server.NetworkListener
import org.glassfish.grizzly.http2.Http2AddOn
import org.glassfish.grizzly.utils.Charsets
import org.glassfish.hk2.api.ServiceLocator
import org.glassfish.jersey.grizzly2.httpserver.HttpGrizzlyContainer
import org.glassfish.jersey.internal.guava.ThreadFactoryBuilder
import org.glassfish.jersey.process.JerseyProcessingUncaughtExceptionHandler
import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.server.spi.Container
import org.glassfish.jersey.server.spi.ContainerLifecycleListener
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class FeatureHubJerseyHost constructor(private val config: ResourceConfig) {
  private val log: Logger = LoggerFactory.getLogger(FeatureHubJerseyHost::class.java)

  @ConfigKey("server.port")
  var port: Int? = 8903

  @ConfigKey("server.gracePeriodInSeconds")
  var gracePeriod: Int? = 10

  var allowedWebHosting = true

  init {
    DeclaredConfigResolver.resolve(this)

    config.register(
      CommonFeatureHubFeatures::class.java,
    ).register(object : ContainerLifecycleListener {
      override fun onStartup(container: Container) {
        // access the ServiceLocator here
        val injector = container
          .applicationHandler
          .injectionManager
          .getInstance(ServiceLocator::class.java)

        val drainSources = injector.getAllServices(ExecutorPoolDrainageSource::class.java)

        ApplicationLifecycleManager.registerListener { trans ->
          if (trans.next == LifecycleStatus.TERMINATING) {
            for (drainSource in drainSources) {
              drainSource.drain()
            }
          }
        }
      }

      override fun onReload(container: Container) {
      }

      override fun onShutdown(container: Container) {
      }
    })
  }

  companion object {
    /**
     * We cannot use ContainerLifecycleListener as a generic callback as we don't know what container stuff is going
     * into so we can't glob them together and you cannot register the same classname > 1 time with the process. Always
     * use the ContainerLifecycleListener in-situ and use this function if you want the service locator. If you want services
     * to be available at startup, use @Immediate
     */
    fun withServiceLocator(container: Container, locate: (serviceLocator: ServiceLocator) -> Unit) {
      val serviceLocator = container
        .applicationHandler
        .injectionManager
        .getInstance(ServiceLocator::class.java)

      locate(serviceLocator)
    }
  }

  fun disallowWebHosting(): FeatureHubJerseyHost {
    allowedWebHosting = false
    return this
  }

  fun start(): FeatureHubJerseyHost {
    return start(port!!)
  }

  fun start(overridePort: Int): FeatureHubJerseyHost {
    val offsetPath = FallbackPropertyConfig.getConfig("featurehub.url-path", "/")
    val BASE_URI = URI.create(String.format("http://0.0.0.0:%d%s", overridePort, offsetPath))
//    val server = GrizzlyHttpServerFactory.createHttpServer(BASE_URI, config, false)

    val listener = NetworkListener("grizzly", NetworkListener.DEFAULT_NETWORK_HOST, overridePort)

    listener.transport.workerThreadPoolConfig.threadFactory = ThreadFactoryBuilder()
      .setNameFormat("grizzly-http-server-%d")
      .setUncaughtExceptionHandler(JerseyProcessingUncaughtExceptionHandler())
      .build()

    listener.registerAddOn(Http2AddOn())

    val server = HttpServer()
    server.addListener(listener)

    val serverConfig = server.serverConfiguration

    val resourceHandler = HttpGrizzlyContainer.makeHandler(config)

    val contextPath: String =
      if (offsetPath.endsWith("/")) offsetPath.substring(0, offsetPath.length - 1) else offsetPath
    if (allowedWebHosting && FallbackPropertyConfig.getConfig("run.nginx") != null) {
      log.info("starting with web asset support")
      serverConfig.addHttpHandler(
        DelegatingHandler(resourceHandler, AdminAppStaticHttpHandler(offsetPath)),
        HttpHandlerRegistration.Builder().contextPath(contextPath).urlPattern("").build()
      )
    } else {
      serverConfig.addHttpHandler(
        resourceHandler,
        HttpHandlerRegistration.Builder().contextPath(contextPath).urlPattern("").build()
      );
    }


    serverConfig.isPassTraceRequest = true
    serverConfig.defaultQueryEncoding = Charsets.UTF8_CHARSET

    ApplicationLifecycleManager.registerListener { trans: LifecycleTransition ->
      if (trans.next == LifecycleStatus.TERMINATING) {
        try {
          server.shutdown(gracePeriod!!.toLong(), TimeUnit.SECONDS).get()
        } catch (e: InterruptedException) {
          log.error("Failed to shutdown server in {} seconds", gracePeriod)
        } catch (e: ExecutionException) {
          log.error("Failed to shutdown server in {} seconds", gracePeriod)
        }
      }
    }

    server.start()

    log.info("server started on {} with http/2 enabled", BASE_URI.toString())

    return this
  }
}
