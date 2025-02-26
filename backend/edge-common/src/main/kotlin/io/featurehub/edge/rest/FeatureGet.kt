package io.featurehub.edge.rest

import cd.connect.app.config.ConfigKey
import cd.connect.app.config.DeclaredConfigResolver
import io.featurehub.edge.KeyParts
import io.featurehub.edge.features.DachaFeatureRequestSubmitter
import io.featurehub.edge.features.ETagSplitter.Companion.makeEtags
import io.featurehub.edge.features.ETagSplitter.Companion.splitTag
import io.featurehub.edge.features.FeatureRequestResponse
import io.featurehub.edge.features.FeatureRequestSuccess
import io.featurehub.edge.stats.StatRecorder
import io.featurehub.edge.strategies.ClientContext
import io.featurehub.sse.stats.model.EdgeHitResultType
import io.featurehub.sse.stats.model.EdgeHitSourceType
import io.featurehub.utils.FeatureHubConfig
import io.prometheus.client.Gauge
import io.prometheus.client.Histogram
import jakarta.inject.Inject
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.container.AsyncResponse
import jakarta.ws.rs.core.Response
import java.text.DateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.function.Consumer
import java.util.stream.Collectors

interface FeatureGet {
  fun processGet(
    response: AsyncResponse,
    sdkUrls: List<String>?,
    apiKeys: List<String>?,
    featureHubAttrs: List<String>?,
    etagHeader: String?,
    statRecorder: StatRecorder?
  )
}

class FeatureGetProcessor @Inject constructor(
  private val getOrchestrator: DachaFeatureRequestSubmitter,
  @FeatureHubConfig("edge.cache-control.header")
  private val cacheControlHeader: String?
  ) : FeatureGet {

  override fun processGet(
    response: AsyncResponse,
    sdkUrls: List<String>?,
    apiKeys: List<String>?,
    featureHubAttrs: List<String>?,
    etagHeader: String?,
    statRecorder: StatRecorder?
  ) {
    if ((sdkUrls == null || sdkUrls.isEmpty()) && (apiKeys == null || apiKeys.isEmpty())) {
      response.resume(BadRequestException())
      return
    }

    inout.inc()

    val timer: Histogram.Timer = pollSpeedHistogram.startTimer()

    val realApiKeys = (if (sdkUrls == null || sdkUrls.isEmpty()) apiKeys else sdkUrls)!!
      .asSequence()
      .distinct() // we want unique ones
      .map { KeyParts.fromString(it) }
      .filterNotNull()
      .toList()

    if (realApiKeys.isEmpty()) {
      response.resume(NotFoundException())
      return
    }

    val clientContext = ClientContext.decode(featureHubAttrs, realApiKeys)
    val etags = splitTag(etagHeader, realApiKeys, clientContext.makeEtag())

    val environments: List<FeatureRequestResponse> = getOrchestrator.request(realApiKeys, clientContext, etags)

    if (statRecorder != null) {
      // record the result
      environments.forEach(Consumer { resp: FeatureRequestResponse ->
        with(statRecorder) {
          recordHit(
            resp.key, mapSuccess(resp.success),
            EdgeHitSourceType.POLL
          )
        }
      })
    }

    timer.observeDuration()

    inout.dec()

    if (environments[0].success === FeatureRequestSuccess.NO_CHANGE) {
      response.resume(wrapCacheControl(Response.status(304).header("etag", etagHeader)).build())
    } else if (environments.all { it.success == FeatureRequestSuccess.NO_SUCH_KEY_IN_CACHE }) {
      response.resume(Response.status(404).build())
    } else if (environments.all { it.success == FeatureRequestSuccess.DACHA_NOT_READY }) {
      // all the SDKs fail on a 400 level error and just stop.
      response.resume(Response.status(503).entity("cache layer not ready, try again shortly").build())
    } else {
      val newEtags = makeEtags(
        etags,
        environments.stream().map(FeatureRequestResponse::etag).collect(Collectors.toList()))

      response.resume(
        wrapCacheControl(
        Response.status(200)
          .header("etag", "\"${newEtags}\"")
          .entity(environments.stream().map(FeatureRequestResponse::environment).collect(Collectors.toList()))
        )
          .build()
      )
    }
  }

  private fun wrapCacheControl(builder: Response.ResponseBuilder): Response.ResponseBuilder {
    if (cacheControlHeader?.isNotEmpty() == true) {
      return builder.header("Cache-Control", cacheControlHeader)
    }

    return builder
  }

  private fun mapSuccess(success: FeatureRequestSuccess): EdgeHitResultType {
    return when (success) {
      FeatureRequestSuccess.NO_SUCH_KEY_IN_CACHE -> EdgeHitResultType.MISSED
      FeatureRequestSuccess.SUCCESS -> EdgeHitResultType.SUCCESS
      FeatureRequestSuccess.NO_CHANGE -> EdgeHitResultType.NO_CHANGE
      FeatureRequestSuccess.DACHA_NOT_READY -> EdgeHitResultType.MISSED
    }
  }

  companion object {
    val inout = Gauge.build("edge_get_req", "how many GET requests").register()
    val pollSpeedHistogram = Histogram.build(
      "edge_conn_length_poll", "The length of " +
        "time that the connection is open for Polling clients"
    ).register()
  }

}
