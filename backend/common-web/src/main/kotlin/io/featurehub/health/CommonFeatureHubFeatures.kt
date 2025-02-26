package io.featurehub.health

import cd.connect.jersey.common.LoggingConfiguration
import cd.connect.jersey.prometheus.PrometheusDynamicFeature
import cd.connect.openapi.support.ReturnStatusContainerResponseFilter
import io.featurehub.info.ApplicationVersionFeatures
import io.featurehub.jersey.config.CommonConfiguration
import io.featurehub.jersey.config.EndpointLoggingListener
import io.featurehub.utils.ConfigInjectionResolver
import io.featurehub.utils.CurrentTime
import io.featurehub.utils.CurrentTimeSource
import io.featurehub.utils.FeatureHubConfig
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.ws.rs.core.Feature
import jakarta.ws.rs.core.FeatureContext
import org.glassfish.hk2.api.ServiceLocator
import org.glassfish.hk2.api.TypeLiteral
import org.glassfish.hk2.utilities.ServiceLocatorUtilities
import org.glassfish.jersey.internal.inject.*
import org.glassfish.jersey.server.ServerProperties


class CommonFeatureHubFeatures @Inject constructor(private val locator: ServiceLocator) : Feature {

  // ensure Immediate scope which is HK2 specific is enabled everywhere
  init {
    ServiceLocatorUtilities.enableImmediateScope(locator)
  }

  override fun configure(context: FeatureContext): Boolean {
    context.property(ServerProperties.BV_SEND_ERROR_IN_RESPONSE, true)

    context.register(CommonConfiguration::class.java)
    context.register(LoggingConfiguration::class.java)
    context.register(ReturnStatusContainerResponseFilter::class.java)
    context.register(EndpointLoggingListener::class.java)
    context.register(PrometheusDynamicFeature::class.java)
    context.register(ApplicationVersionFeatures::class.java)

    context.register(object : AbstractBinder() {
      override fun configure() {
        bind(CurrentTime::class.java).to(CurrentTimeSource::class.java).`in`(
          Singleton::class.java
        )
        bind(ConfigInjectionResolver())
      }
    })

    return true
  }
}
