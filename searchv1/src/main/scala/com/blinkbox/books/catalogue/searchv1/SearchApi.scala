package com.blinkbox.books.catalogue.searchv1

import akka.actor.ActorRefFactory
import com.blinkbox.books.config.ApiConfig
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.logging.DiagnosticExecutionContext
import com.blinkbox.books.spray.{Directives, _}
import org.json4s.Formats
import org.slf4j.LoggerFactory
import spray.http.StatusCodes
import spray.httpx.Json4sJacksonSupport
import spray.routing._

class SearchApi(apiConfig: ApiConfig, searchService: V1SearchService)(implicit val actorRefFactory: ActorRefFactory)
    extends HttpService
    with Directives
    with Json4sJacksonSupport {

  implicit val log = LoggerFactory.getLogger(classOf[SearchApi])
  implicit def json4sJacksonFormats: Formats = DefaultFormats
  implicit val executionContext = DiagnosticExecutionContext(actorRefFactory.dispatcher)

  val BookIdSegment = Segment.map(BookId.apply _)

  val serviceRoutes: Route = get {
    pathPrefix("catalogue" / "search") {
      pathPrefix("books") {
        pathEnd {
          get {
            parameter('q.?) { query =>
              query.fold[Route](complete(StatusCodes.BadRequest)) { query =>
                onSuccess(searchService.search(query)) { res =>
                  complete(res)
                }
              }
            }
          }
        } ~
        path(BookIdSegment / "similar") { bookId =>
          get {
            complete(searchService.similar(bookId))
          }
        }
      } ~
      path("suggestions") {
        get {
          parameter('q) { q =>
            complete(searchService.suggestions(q))
          }
        }
      }
    }
  }

  def routes: Route = rootPath(apiConfig.localUrl.path) {
    monitor() {
      serviceRoutes
    }
  }
}