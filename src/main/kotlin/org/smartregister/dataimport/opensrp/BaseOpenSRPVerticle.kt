package org.smartregister.dataimport.opensrp

import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.client.HttpResponse
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.awaitEvent
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.smartregister.dataimport.openmrs.OpenMRSLocationVerticle
import org.smartregister.dataimport.shared.*
import org.smartregister.dataimport.shared.model.LocationTag
import kotlin.math.ceil

/**
 * Subclass of [BaseVerticle] which is the base class for all classes with OpenSRP related code
 */
abstract class BaseOpenSRPVerticle : BaseVerticle() {

  protected var locationTagsMap = mapOf<String, LocationTag>()

  /**
   * This function consumes the messaged in the event bus from the [countAddress]. Once A count has been received,
   * the function request for data from OpenMRS periodically (as configured via the [requestInterval] property)
   * via event bus through [loadAddress]. This will prompt querying OpenMRS
   * database for data once the data is received the callback method [action] with the data as its parameter.
   * [dataItem]
   */
  protected fun consumeOpenMRSData(
    dataItem: DataItem, countAddress: String, loadAddress: String, action: suspend (JsonArray) -> Unit
  ) {
    val eventBus = vertx.eventBus()
    eventBus.consumer<Int>(countAddress).handler { countMessage ->
      var offset = 0
      val count = countMessage.body()
      val numberOfRequests = ceil(count.toDouble().div(limit.toDouble()))

      launch(vertx.dispatcher()) {
        try {
          startVertxCounter(dataItem = dataItem, dataSize = numberOfRequests.toLong())
          while (offset <= count) {
            awaitEvent<Long> { vertx.setTimer(getRequestInterval(dataItem), it) }
            val message = awaitResult<Message<JsonArray>> { handler ->
              eventBus.request(loadAddress, offset, handler)
            }
            action(message.body())
            offset += limit
          }
        } catch (throwable: Throwable) {
          vertx.exceptionHandler().handle(throwable)
        }
      }
    }

    eventBus.consumer<String>(EventBusAddress.OPENMRS_TASK_COMPLETE).handler {
      when (DataItem.valueOf(it.body())) {
        DataItem.LOCATION_TAGS -> launch(vertx.dispatcher()) {
          retrieveLocationTags()
          deployVerticle(OpenMRSLocationVerticle(), poolName = OPENMRS_LOCATIONS)
        }
        DataItem.KEYCLOAK_USERS -> vertx.eventBus().send(EventBusAddress.OPENMRS_KEYCLOAK_USERS_GROUP_ASSIGN, true)
        else -> eventBus.send(EventBusAddress.APP_SHUTDOWN, true)
      }
    }
  }

  /**
   * Post [data] to the provided [url] keeping track of the number of responses from the server.This will help in correctly
   * determining whether the task is fully completed. The counter starts off with number of requests sent to the server.
   * When 0 it means that was the last request.
   */
  protected suspend inline fun <reified T> postData(url: String, data: List<T>, dataItem: DataItem) {
    try {
      val item = dataItem.name.lowercase()
      logger.info("Posting ${data.size} $item data to OpenSRP")
      val json: String = jsonEncoder().encodeToString(data)
      val counter = vertx.sharedData().getCounter(dataItem.name).await()

      awaitResult<HttpResponse<Buffer>?> {
        webRequest(url = url, payload = json, handler = it)
      }?.run {
        logHttpResponse()
        logger.info("Posted ${data.size} $item to OpenSRP")
        checkTaskCompletion(counter, dataItem)
      }
    } catch (throwable: Throwable) {
      vertx.exceptionHandler().handle(throwable)
    }
  }

  protected inline fun <reified T> sendData(address: String, data: List<T>) {
    val payload = JsonArray(jsonEncoder().encodeToString(data))
    vertx.eventBus().send(address, payload)
  }

  protected suspend fun retrieveLocationTags() {
    val locationTags =
      awaitResult<HttpResponse<Buffer>?> {
        webRequest(
          method = HttpMethod.GET,
          url = config.getString("opensrp.rest.location.tag.url"),
          handler = it
        )
      }
        ?.body()
    if (locationTags != null && config.getString(SOURCE_FILE).isNullOrBlank()) {
      locationTagsMap =
        Json.decodeFromString<List<LocationTag>>(locationTags.toString()).associateBy { it.name }
    }
  }

}
