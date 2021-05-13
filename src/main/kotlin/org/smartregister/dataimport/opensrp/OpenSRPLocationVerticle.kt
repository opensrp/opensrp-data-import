package org.smartregister.dataimport.opensrp

import com.opencsv.CSVReaderBuilder
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.HttpResponse
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.awaitResult
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.smartregister.dataimport.keycloak.KeycloakUserVerticle
import org.smartregister.dataimport.openmrs.OpenMRSLocationVerticle
import org.smartregister.dataimport.shared.*
import org.smartregister.dataimport.shared.model.*
import java.io.FileReader
import java.io.IOException
import java.util.*

/**
 * Subclass of [BaseOpenSRPVerticle] responsible for posting OpenSRP locations
 * This verticle can get data from either openmrs or CSV. Using CSV, gives more flexibility
 */
class OpenSRPLocationVerticle : BaseOpenSRPVerticle() {

  private var locationTagsMap = mapOf<String, LocationTag>()

  private var locationIds = mutableMapOf<String, String>()

  private var organizationUsers = mapOf<String, List<KeycloakUser>>()

  private var keycloakUsers = listOf<KeycloakUser>()

  private val organizations = mutableListOf<Organization>()

  private val organizationLocations = mutableListOf<OrganizationLocation>()

  private val userIdsMap = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)

  override suspend fun start() {
    super.start()

    val sourceFile = config.getString(SOURCE_FILE)

    if (sourceFile.isNullOrBlank()) {
      vertx.deployVerticle(OpenMRSLocationVerticle())
      consumeOpenMRSData(
        countAddress = EventBusAddress.OPENMRS_LOCATIONS_COUNT,
        loadAddress = EventBusAddress.OPENMRS_LOCATIONS_LOAD,
        action = this::postLocations
      )
    } else {
      try {
        val usersFile = config.getString(USERS_FILE)

        if (usersFile.isNotBlank()) {
          extractUsersFromCSV(usersFile)
          deployVerticle(KeycloakUserVerticle(), commonConfigs())
        }

        extractLocationsFromCSV(sourceFile)

        cascadeDataImportation()
        //Update Map with keycloak user ids
        updateUserIds(userIdsMap)
      } catch (throwable: Throwable) {
        vertx.exceptionHandler().handle(throwable)
      }
    }
  }


  private fun cascadeDataImportation() {

    //Begin by posting locations from CSV, then organization, map organizations to locations, post keycloak users,
    // create practitioners and finally assign practitioners to organizations
    try {
      vertx.eventBus().consumer<String>(EventBusAddress.TASK_COMPLETE).handler { message ->
        when (DataItem.valueOf(message.body())) {
          DataItem.LOCATIONS -> {
            val organizationsChunked = organizations.chunked(limit)
            consumeCSVData(organizationsChunked, DataItem.ORGANIZATIONS) {
              postData(config.getString("opensrp.rest.organization.url"), it, DataItem.ORGANIZATIONS)
            }
          }
          DataItem.ORGANIZATIONS -> {
            val organizationLocationsChunked = organizationLocations.chunked(limit)
            consumeCSVData(organizationLocationsChunked, DataItem.ORGANIZATION_LOCATIONS) {
              postData(
                config.getString("opensrp.rest.organization.location.url"), it, DataItem.ORGANIZATION_LOCATIONS
              )
            }
          }
          DataItem.ORGANIZATION_LOCATIONS -> {
            keycloakUsers = organizationUsers.map { it.value }.flatten().onEach {
              it.organizationLocationId = locationIds[it.parentLocation + it.location]
            }

            logger.info("Posting ${keycloakUsers.size} users to Keycloak")

            val keycloakUsersChunked = keycloakUsers.chunked(limit)
            consumeCSVData(keycloakUsersChunked, DataItem.KEYCLOAK_USERS) {
              sendData(EventBusAddress.CSV_KEYCLOAK_USERS_LOAD,  DataItem.KEYCLOAK_USERS, it)
            }
          }
          DataItem.KEYCLOAK_USERS -> {
            val usernames = keycloakUsers.filter { it.username != null }.map { it.username!! }
            val usernamesChunked = usernames.chunked(limit)
            consumeCSVData(usernamesChunked, DataItem.KEYCLOAK_USERS_GROUPS) {
              sendData(EventBusAddress.CSV_KEYCLOAK_USERS_GROUP_ASSIGN,  DataItem.KEYCLOAK_USERS_GROUPS, it)
            }
          }
          else -> logger.warn("IDLING...(Press Ctrl + C) to shutdown)")
        }
      }
    } catch (throwable: Throwable) {
      vertx.exceptionHandler().handle(throwable)
    }
  }

  private suspend fun postLocations(locations: JsonArray) {
    locations.forEach { location ->
      //Delete locationTags attributes for locations without tags
      if (location is JsonObject && location.containsKey(LOCATION_TAGS)) {
        val locationTags = location.getJsonArray(LOCATION_TAGS)
        locationTags.forEach { tag ->
          if (tag is JsonObject && tag.getString(ID) == null) {
            location.remove(LOCATION_TAGS)
          }
        }
      }
    }
    awaitResult<HttpResponse<Buffer>?> {
      webRequest(
        url = config.getString("opensrp.rest.location.url"),
        payload = locations,
        handler = it
      )
    }?.logHttpResponse()
  }

  private suspend fun extractUsersFromCSV(usersFile: String) {
    organizationUsers = vertx.executeBlocking<Map<String, List<KeycloakUser>>> { promise ->
      try {
        val users = readCsvData<KeycloakUser>(usersFile, true, 1)
          .groupBy { it.parentLocation + it.location }
        promise.complete(users)
      } catch (exception: IOException) {
        vertx.exceptionHandler().handle(exception)
      }
    }.await()
  }

  private suspend fun extractLocationsFromCSV(sourceFile: String?) {
    val geoLevels = config.getString("location.hierarchy")
      .split(',').associateByTo(mutableMapOf(), { key: String ->
        key.split(":").first().trim()
      }, { value: String ->
        value.split(":").last().trim().toInt()
      })

    //Generate team and users for locations tagged with hasTeam
    val generateTeams = config.getString(GENERATE_TEAMS, "")

    val locationTags = awaitResult<HttpResponse<Buffer>?> {
      webRequest(
        method = HttpMethod.GET,
        url = config.getString("opensrp.rest.location.tag.url"),
        handler = it
      )
    }?.body()

    if (locationTags != null && sourceFile != null) {

      locationTagsMap = Json.decodeFromString<List<LocationTag>>(locationTags.toString()).associateBy { it.name }

      val locationsData: List<List<Location>> = vertx.executeBlocking<List<List<Location>>> { promise ->

        val locations = mutableListOf<Location>()

        try {
          val csvReader = CSVReaderBuilder(FileReader(sourceFile)).build()
          var cells = csvReader.readNext()
          val headers = cells
          var counter = 1
          if (validateHeaders(headers, promise)) {
            while (cells != null) {
              if (counter > 1) {
                locations.addAll(processLocations(headers, cells, geoLevels))
              }
              cells = csvReader.readNext()
              counter++
            }
          }
        } catch (exception: NoSuchFileException) {
          logError(promise, exception.localizedMessage)
        }

        locations.filter { it.hasTeam }.associateBy { it.id }.map { it.value }.forEach {
          if (generateTeams.isNotBlank()) {
            createOrganizations(it)
          }
        }
        val newLocations = locations.filter { it.isNew }.associateBy { it.id }.map { it.value }.chunked(limit)
        promise.complete(newLocations)
      }.await()

      consumeCSVData(csvData = locationsData, DataItem.LOCATIONS) {
        postData(config.getString("opensrp.rest.location.url"), it, DataItem.LOCATIONS)
      }
    }
  }

  private fun validateHeaders(headers: Array<String>, promise: Promise<List<List<Location>>>): Boolean {

    //Columns must be at least 4 and even number
    val isSizeValid = headers.size % 2 == 0 && headers.size >= 4
    if (!isSizeValid) {
      logError(promise, "Error: CSV format not valid - expected an even number of at least 4 columns")
      return false
    }

    //Format: Location level ID column followed by the level e.g. Country Id, Country, Province Id, Province
    headers.toList().chunked(2).onEach {
      val (levelId, level) = it

      if (!locationTagsMap.containsKey(level)) {
        logError(promise, "Error: Location tag $level does not exist. Import location tags and continue.")
        return false
      }

      if (!levelId.endsWith(ID, true) || !levelId.split(" ").containsAll(level.split(" "))) {
        logError(
          promise, """
          Error: INCORRECT format for columns ($levelId and $level)
          Columns MUST be named in the order of location levels with the id column preceding e.g. Country Id, Country, Province Id, Province
        """.trimIndent()
        )
        return false
      }
    }
    return isSizeValid
  }

  private fun logError(promise: Promise<List<List<Location>>>, message: String, throwable: Throwable? = null) {
    promise.fail(message)
    if (throwable != null) {
      vertx.exceptionHandler().handle(throwable)
    } else {
      vertx.exceptionHandler().handle(DataImportException(message))
    }
  }

  private fun processLocations(header: Array<String>, cells: Array<String>, geoLevels: MutableMap<String, Int>)
    : List<Location> {

    var parentIdPos = 0
    var parentNamePos = 1
    var idPos = 2
    var namePos = 3

    val locations = mutableListOf<Location>()
    val zippedList = header.zip(cells) //Combine header with row to know the correct location level for the value

    do {
      val locationTag = zippedList[namePos].first
      val parentId = zippedList[parentIdPos].second
      val name = zippedList[namePos].second
      val key = zippedList[parentNamePos].second.plus(name)
      var isNewLocation = false

      //Generate ids for new locations. Also track team location ids
      var id = zippedList[idPos].second
      if (id.isBlank()) {
        id = locationIds.getOrPut(key) { UUID.randomUUID().toString() }
        isNewLocation = true
      }
      val hasTeam = config.getString(GENERATE_TEAMS, "").equals(locationTag, ignoreCase = true)
      if (hasTeam) {
        locationIds[key] = id
      }

      val location = Location(
        id = id,
        locationTags = listOf(locationTagsMap.getValue(locationTag)),
        properties = LocationProperties(
          parentId = parentId,
          name = name,
          geographicalLevel = geoLevels.getOrDefault(locationTag, 0)
        ),
        isNew = isNewLocation,
        hasTeam = hasTeam,
        uniqueName = key
      )

      locations.add(location)

      parentIdPos += 2
      parentNamePos = parentIdPos + 1
      idPos = parentIdPos + 2
      namePos = idPos + 1

    } while (parentIdPos <= zippedList.size / 2)

    //Use previous location id as the parent id of the next - also include the first item in the list which is always ignored with zip
    return locations.subList(0, 1)
      .plus(locations.zipWithNext().map {
        it.second.copy().apply { properties.parentId = it.first.id }
      })
  }

  private fun createOrganizations(location: Location) {
    with(location) {
      try {
        val organizationId = UUID.randomUUID().toString()

        val organization = Organization(identifier = organizationId, name = "Team ${properties.name}")
        organizations.add(organization)

        vertx.eventBus().send(EventBusAddress.CSV_GENERATE, JsonObject().apply {
          put(ACTION, DataItem.ORGANIZATIONS.name.lowercase())
          put(PAYLOAD, JsonObject(jsonEncoder().encodeToString(organization)))
        })

        val organizationLocation = OrganizationLocation(organizationId, id)
        organizationLocations.add(organizationLocation)
        vertx.eventBus().send(EventBusAddress.CSV_GENERATE, JsonObject().apply {
          put(ACTION, DataItem.ORGANIZATION_LOCATIONS.name.lowercase())
          put(PAYLOAD, JsonObject(jsonEncoder().encodeToString(organizationLocation)))
        })

      } catch (throwable: Throwable) {
        vertx.exceptionHandler().handle(throwable)
      }
    }
  }
}
