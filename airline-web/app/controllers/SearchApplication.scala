package controllers

import com.patson.data.{AllianceSource, ConsumptionHistorySource, LinkSource}
import com.patson.model.Scheduling.TimeSlot
import com.patson.model.{PassengerType, _}
import javax.inject.Inject
import play.api.libs.json._
import play.api.mvc._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Random


class SearchApplication @Inject()(cc: ControllerComponents) extends AbstractController(cc) {
  implicit object SearhResultWrites extends Writes[(SimpleRoute, Int)] {
    def writes(route: (SimpleRoute, Int)): JsValue = {
      var result = Json.obj("passenger" -> route._2)
      var routeJson = Json.arr()
      var index = 0
      var previousArrivalMinutes = 0
      val schedule : Map[Link, TimeSlot] = generateRouteSchedule(route._1.links.map(_._1)).toMap
      val detailedLinkCache : mutable.HashMap[Int, Option[Link]] = mutable.HashMap()
      route._1.links.foreach {
        case(link, linkClass, inverted) => {
          val (from, to) = if (!inverted) (link.from, link.to) else (link.to, link.from)
          val departureMinutes = adjustMinutes(schedule(link).totalMinutes, previousArrivalMinutes)
          val arrivalMinutes = departureMinutes + link.duration
          var linkJson = Json.obj(
            "airlineName" -> link.airline.name,
            "airlineId" -> link.airline.id,
            "flightCode" -> LinkUtil.getFlightCode(link.airline, link.flightNumber),
            "linkClass" -> linkClass.label,
            "fromAirportId" -> from.id,
            "fromAirportName" -> from.name,
            "fromAirportIata" -> from.iata,
            "fromAirportCity" -> from.city,
            "fromAirportCountryCode" -> from.countryCode,
            "toAirportId" -> to.id,
            "toAirportName" -> to.name,
            "toAirportIata" -> to.iata,
            "toAirportCity" -> to.city,
            "duration" -> link.duration,
            "toAirportCountryCode" -> to.countryCode,
            "price" -> link.price(linkClass),
            "departure" -> departureMinutes,
            "arrival" -> arrivalMinutes
           )

          detailedLinkCache.getOrElseUpdate(link.id, LinkSource.loadLinkById(link.id)).foreach { detailedLink =>
            linkJson = linkJson + ("computedQuality" -> JsNumber(detailedLink.computedQuality))
            detailedLink.getAssignedModel().foreach { model =>
              linkJson = linkJson + ("airplaneModelName" -> JsString(model.name))
            }
            linkJson = linkJson + ("features" -> Json.toJson(getLinkFeatures(detailedLink).map(_.toString)))
          }

          previousArrivalMinutes = arrivalMinutes

          routeJson = routeJson.append(linkJson)
          index += 1
        }
      }

      result = result + ("route" -> routeJson) + ("passenger" -> JsNumber(route._2))
      result
    }
  }

  implicit object AirportSearchResultWrites extends Writes[AirportSearchResult] {
    def writes(airportSearchResult : AirportSearchResult) : JsValue = {
      Json.obj(
        "airportId" -> airportSearchResult.getId,
        "airportName" -> airportSearchResult.getName,
        "airportIata" -> airportSearchResult.getIata,
        "airportCity" -> airportSearchResult.getCity,
        "countryCode" -> airportSearchResult.getCountryCode,
        "score" -> airportSearchResult.getScore)

    }
  }




  def searchRoute(fromAirportId : Int, toAirportId : Int) = Action {
    val routes: List[(SimpleRoute, PassengerType.Value, Int)] = ConsumptionHistorySource.loadConsumptionsByAirportPair(fromAirportId, toAirportId).toList.sortBy(_._2._2).map {
      case ((route, (passengerType, passengerCount))) =>
        (SimpleRoute(route.links.map(linkConsideration => (linkConsideration.link, linkConsideration.linkClass, linkConsideration.inverted))), passengerType, passengerCount)
    }

    val reverseRoutes : List[(SimpleRoute, PassengerType.Value, Int)] = ConsumptionHistorySource.loadConsumptionsByAirportPair(toAirportId, fromAirportId).toList.sortBy(_._2._2).map {
      case ((route, (passengerType, passengerCount))) =>
        (SimpleRoute(route.links.reverse.map(linkConsideration => (linkConsideration.link, linkConsideration.linkClass, !linkConsideration.inverted))), passengerType, passengerCount)
    }

    println(s"Search route found ${routes.length} route(s)")
//    println(routes.groupBy(_._1).size)

    val sortedRoutes: List[(SimpleRoute, Int)] = (routes ++ reverseRoutes).groupBy(_._1).view.mapValues( _.map(_._3).sum).toList.sortBy(_._1.totalPrice)
    val allianceMap = AllianceSource.loadAllAlliances().map(alliance => (alliance.id, alliance)).toMap

    val targetAirlines = mutable.Set[Airline]()
    val targetLinkIds = mutable.Set[Int]()
    val targetAirports = mutable.Set[Airport]()
    //iterate once to gather some summary info
    sortedRoutes.foreach {
      case (route, passengerCount) =>
        targetAirlines.addAll(route.links.map(_._1.airline))
        targetLinkIds.addAll(route.links.map(_._1.id))
        targetAirports.add(route.links(0)._1.from)
        targetAirports.addAll(route.links.map(_._1.to))
    }
    val airlineAllianceMap = AllianceSource.loadAllianceMemberByAirlines(targetAirlines.toList)
    val detailedLinkLookup = LinkSource.loadLinksByIds(targetLinkIds.toList).map(link => (link.id, link)).toMap

    val airlineGroupLookup = targetAirlines.map { airline => //airline => airline + alliance members
      val airlineGroup : List[Airline] =
        airlineAllianceMap.get(airline) match {
          case Some(allianceMember) =>
            if (allianceMember.role != AllianceRole.APPLICANT) {
              val alliance = allianceMap(allianceMember.allianceId)
              if (alliance.status == AllianceStatus.ESTABLISHED) { //yike check should not be done here
                alliance.members.filterNot(_.role == AllianceRole.APPLICANT).map(_.airline) //yike again!
              } else {
                List(airline)
              }
            } else {
              List(airline)
            }
          case None =>
            List(airline)
        }
      (airline, airlineGroup)
    }.toMap

    //[airline => airline + alliance members

    val remarks : Map[SimpleRoute, List[LinkRemark.Value]] = generateRemarks(sortedRoutes)

    //generate the final json
    var resultJson = Json.arr()
    sortedRoutes.foreach {
      case(route, passengerCount) =>
        var routeEntryJson = Json.obj()
        var routeJson = Json.arr()
        var index = 0
        var previousArrivalMinutes = 0
        val schedule : Map[Link, TimeSlot] = generateRouteSchedule(route.links.map(_._1)).toMap
        val startAirline = route.links(0)._1.airline
        route.links.foreach {
          case(link, linkClass, inverted) => {
            val (from, to) = if (!inverted) (link.from, link.to) else (link.to, link.from)
            val departureMinutes = adjustMinutes(schedule(link).totalMinutes, previousArrivalMinutes)
            val arrivalMinutes = departureMinutes + link.duration

            val codeShareFlight : Boolean =
              if (index == 0) {
                false
              } else {
                link.airline != startAirline && airlineGroupLookup(startAirline).contains(link.airline)
              }

            val airline = if (codeShareFlight) startAirline else link.airline
            var linkJson = Json.obj(
              "airlineName" -> airline.name,
              "airlineId" -> airline.id,
              "flightCode" -> LinkUtil.getFlightCode(airline, link.flightNumber),
              "linkClass" -> linkClass.label,
              "fromAirportId" -> from.id,
              "fromAirportName" -> from.name,
              "fromAirportIata" -> from.iata,
              "fromAirportCity" -> from.city,
              "fromAirportCountryCode" -> from.countryCode,
              "toAirportId" -> to.id,
              "toAirportName" -> to.name,
              "toAirportIata" -> to.iata,
              "toAirportCity" -> to.city,
              "duration" -> link.duration,
              "toAirportCountryCode" -> to.countryCode,
              "price" -> link.price(linkClass),
              "departure" -> departureMinutes,
              "arrival" -> arrivalMinutes
            )

            if (codeShareFlight) {
              linkJson = linkJson + ("operatorAirlineName" -> JsString(link.airline.name)) +  ("operatorAirlineId" -> JsNumber(link.airline.id))
            }

            detailedLinkLookup.get(link.id).foreach { detailedLink =>
              linkJson = linkJson + ("computedQuality" -> JsNumber(detailedLink.computedQuality))
              detailedLink.getAssignedModel().foreach { model =>
                linkJson = linkJson + ("airplaneModelName" -> JsString(model.name))
              }

              linkJson = linkJson + ("features" -> Json.toJson(getLinkFeatures(detailedLink).map(_.toString)))
            }
                        previousArrivalMinutes = arrivalMinutes

            routeJson = routeJson.append(linkJson)
            index += 1
          }
        }

        remarks.get(route).foreach { remarks =>
          routeEntryJson = routeEntryJson + ("remarks" -> Json.toJson(remarks.map(_.toString)))
        }

        routeEntryJson = routeEntryJson + ("route" -> routeJson) + ("passenger" -> JsNumber(passengerCount))
        resultJson = resultJson.append(routeEntryJson)
    }

    Ok(resultJson)
  }


  import scala.jdk.CollectionConverters._
  def searchAirport(input : String) = Action {
    if (input.length < 3) {
      Ok(Json.obj("message" -> "Search with at least 3 characters"))
    } else {
      val result: List[AirportSearchResult] = SearchUtil.search(input).asScala.toList
      if (result.isEmpty) {
        Ok(Json.obj("message" -> "No match"))
      } else {
        Ok(Json.obj("airports" -> Json.toJson(result)))
      }
    }
  }

  case class SimpleRoute(links : List[(Link, LinkClass, Boolean)]) {
    val totalPrice = links.map {
      case (link, linkClass, _) => link.price(linkClass)
    }.sum
  }

//
//  def computeLayover(previousLink : Link, currentLink : Link) = {
//    val frequency = previousLink.frequency + currentLink.frequency
//    val random = new Random(previousLink.id)
//    (((random.nextDouble() + 2) * 24 * 60) / frequency).toInt + 15 //some randomness
//  }

  def generateRouteSchedule(links : List[Link]) : List[(Link, TimeSlot)] = {
    val scheduleOptions : ListBuffer[List[(Link, TimeSlot)]] = ListBuffer()
    val random = new Random()
    for (i <- 0 until 7) {
      var previousLinkArrivalTime : TimeSlot = TimeSlot(i, random.nextInt(24), random.nextInt(60))
      val scheduleOption = links.map { link =>
        val departureTime = generateDepartureTime(link, previousLinkArrivalTime.increment(30)) //at least 30 minutes after
        previousLinkArrivalTime = departureTime.increment(link.duration)
        (link, departureTime)
      }
      scheduleOptions.append(scheduleOption)
    }
    val sortedScheduleOptions: mutable.Seq[List[(Link, TimeSlot)]] = scheduleOptions.sortBy {
      case (scheduleOption) =>  {
        var previousArrivalMinutes = 0
        scheduleOption.foreach {
          case (link, departureTimeSlot) => {
            previousArrivalMinutes = adjustMinutes(departureTimeSlot.totalMinutes, previousArrivalMinutes) + link.duration
          }
        }
        //previousArrivalMinutes should contain the minutes of arrival of last leg
        val totalDuration = previousArrivalMinutes - scheduleOption(0)._2.totalMinutes
        totalDuration
      }
    }
    sortedScheduleOptions(0)//find the one with least total duration
  }

  def generateDepartureTime(link : Link, after : TimeSlot) : TimeSlot = {
    val availableSlots = Scheduling.getLinkSchedule(link).sortBy(_.totalMinutes)
    availableSlots.find(_.compare(after) > 0).getOrElse(availableSlots(0)) //find the first slot that is right after the "after option"
  }

  /**
    * Adjust currentMinutes so it's always after the referenceMinutes. This is to handle week rollover on TimeSlot
    * @param currentMinutes
    * @param referenceMinutes
    */
  def adjustMinutes(currentMinutes : Int, referenceMinutes : Int) = {
    var result = currentMinutes
    while (result < referenceMinutes) {
      result += 7 * 24 * 60
    }
    result
  }

  def getLinkFeatures(link : Link) = {
    val features = ListBuffer[LinkFeature.Value]()

    import LinkFeature._
    val airlineServiceQuality = link.airline.getCurrentServiceQuality
    if (link.duration <= 120) { //very short flight
      if (link.rawQuality >= 80) {
        features += BEVERAGE_SERVICE
      }
    } else if (link.duration <= 240) {
      if (link.rawQuality >= 60) {
        features += BEVERAGE_SERVICE
      }
      if (link.rawQuality >= 80) {
        features += HOT_MEAL_SERVICE
      }
    } else {
      if (link.rawQuality >= 40) {
        features += BEVERAGE_SERVICE
      }
      if (link.rawQuality >= 60) {
        features += HOT_MEAL_SERVICE
      }
    }

    if (link.rawQuality == 100 && airlineServiceQuality >= 70) {
      features += PREMIUM_DRINK_SERVICE
    }

    if (link.rawQuality == 100 && airlineServiceQuality >= 85) {
      features += POSH
    }

    link.getAssignedModel().foreach { model =>
      if (model.capacity >= 100) {
        if (airlineServiceQuality >= 50) {
          features += IFE
        }

        if (airlineServiceQuality >= 60) {
          features += POWER_OUTLET
        }

        if (airlineServiceQuality >= 70) {
          features += WIFI
        }

        if (airlineServiceQuality >= 80) {
          features += GAME
        }
      }
    }

    features.toList
  }

  def generateRemarks(routeEntries: List[(SimpleRoute, Int)]): Map[SimpleRoute, List[LinkRemark.Value]] =  {
    val result = mutable.HashMap[SimpleRoute, ListBuffer[LinkRemark.Value]]()
    if (routeEntries.length >= 3) {
      val (maxRoute, maxPassengerCount) = routeEntries.maxBy(_._2)
      result.getOrElseUpdate(maxRoute, ListBuffer()).append(LinkRemark.BEST_SELLER)


      val (cheapestRoute, _) = routeEntries.minBy(_._1.totalPrice)
      result.getOrElseUpdate(cheapestRoute, ListBuffer()).append(LinkRemark.BEST_DEAL)
    }
    result.view.mapValues(_.toList).toMap
  }



  object LinkFeature extends Enumeration {
    type LinkFeature = Value
    val WIFI, BEVERAGE_SERVICE, HOT_MEAL_SERVICE, PREMIUM_DRINK_SERVICE, IFE, POWER_OUTLET, GAME, POSH, LOUNGE = Value //No LOUNGE for now, code is too ugly...
  }

  object LinkRemark extends Enumeration {
    type LinkComment = Value
    val BEST_SELLER, BEST_DEAL = Value
  }



  case class SearchResultRoute(linkDetails : List[LinkDetail], passengerCount: Int)
  case class LinkDetail(link : Link, timeslot : TimeSlot)

}

