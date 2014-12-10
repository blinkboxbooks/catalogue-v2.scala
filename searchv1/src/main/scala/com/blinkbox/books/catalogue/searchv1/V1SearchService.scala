package com.blinkbox.books.catalogue.searchv1

import com.blinkbox.books.catalogue.common.IndexEntities.{SuggestionItem, SuggestionPayload, SuggestionType}
import com.blinkbox.books.catalogue.common.{ElasticsearchConfig, IndexEntities => idx}
import com.blinkbox.books.spray.Page
import com.sksamuel.elastic4s.{ElasticClient, ElasticDsl => E}
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.search.suggest.Suggest
import org.elasticsearch.search.suggest.completion.CompletionSuggestion.Entry
import org.json4s.jackson.Serialization

import scala.collection.convert.Wrappers.{JIteratorWrapper, JListWrapper}
import scala.concurrent.{ExecutionContext, Future}

case class BookId(value: String) extends AnyVal

object V1SearchService {
  val bookSuggestionType = "urn:blinkbox:schema:suggestion:book"
  val contributorSuggestionType = "urn:blinkbox:schema:suggestion:contributor"
  val bookSearchResponseType = "urn:blinkbox:schema:search"
  val bookSimilarResponseType = "urn:blinkbox:schema:search:similar"
  val bookSuggestionResponseType = "urn:blinkbox:schema:list"

  case class Book(id: String, title: String, authors: List[String])
  case class Suggestion(id: String, title: String, `type`: String, authors: Option[List[String]])

  /*
   * TODO: Try using ExplicitTypeHints rather than the `type` parameter as in
   * https://git.mobcastdev.com/Agora/library-service/blob/master/src/main/scala/com/blinkbox/books/agora/LibraryApi.scala
   */
  trait PaginableResponse { def numberOfResults: Long }
  case class BookSearchResponse(
      id: String,
      books: Seq[Book],
      numberOfResults: Long,
      `type`: String = bookSearchResponseType) extends PaginableResponse
  case class BookSimilarResponse(
      books: Seq[Book],
      numberOfResults: Long,
      `type`: String = bookSimilarResponseType) extends PaginableResponse
  case class BookSuggestionResponse(items: Seq[Suggestion], `type`: String = bookSuggestionResponseType)
}

trait V1SearchService {
  import com.blinkbox.books.catalogue.searchv1.V1SearchService._

  def search(q: String, page: Page): Future[BookSearchResponse]
  def similar(bookId: BookId, page: Page): Future[BookSimilarResponse]
  def suggestions(q: String, count: Int): Future[BookSuggestionResponse]
}

class EsV1SearchService(searchConfig: ElasticsearchConfig, client: ElasticClient)(implicit ec: ExecutionContext) extends V1SearchService {
  import com.blinkbox.books.catalogue.common.Json._
  import com.blinkbox.books.catalogue.searchv1.V1SearchService._

  private def toBookSeq(resp: SearchResponse): Seq[Book] =
    resp.getHits.hits().map { hit =>
      val book = Serialization.read[idx.Book](hit.getSourceAsString)
      Book(book.isbn, book.title, book.contributors.map(_.displayName))
    }.toSeq

  private def toBookSearchResponse(q: String)(resp: SearchResponse): BookSearchResponse =
    BookSearchResponse(q, toBookSeq(resp), resp.getHits.getTotalHits)

  private def toBookSimilarResponse(resp: SearchResponse): BookSimilarResponse =
    BookSimilarResponse(toBookSeq(resp), resp.getHits.getTotalHits)

  private def toSuggestionResponse(resp: SearchResponse): BookSuggestionResponse = BookSuggestionResponse(toSuggestionSeq(resp))

  def toSuggestion(payload: SuggestionPayload): Suggestion =
    if (payload.`type` == SuggestionType.Book) {
      val bookItem = payload.item.asInstanceOf[SuggestionItem.Book]
      Suggestion(bookItem.isbn, bookItem.title, bookSuggestionType, Some(bookItem.authors))
    } else {
      val contributorItem = payload.item.asInstanceOf[SuggestionItem.Contributor]
      Suggestion(contributorItem.id, contributorItem.displayName, contributorSuggestionType, None)
    }

  private def toSuggestionSeq(resp: SearchResponse): Seq[Suggestion] =
    (for {
      autoComplete <- JIteratorWrapper(resp.getSuggest.getSuggestion[Suggest.Suggestion[Entry]]("autoComplete").iterator)
      option <- JListWrapper(autoComplete.getOptions)
      payload = Serialization.read[SuggestionPayload](option.getPayloadAsString)
    } yield toSuggestion(payload)).toSeq

  private def searchIn(`type`: String) = E.search in s"${searchConfig.indexName}/${`type`}"

  override def search(q: String, page: Page): Future[BookSearchResponse] =
    client execute {
      searchIn("book") query {
        E.filteredQuery query {
          E.dismax query(
            E.termQuery("isbn", q) boost 4,
            // Query for the title - give precedence to title that match including stop-words
            E.dismax query(
              E.matchPhrase("title", q) boost 1 slop 10,
              E.matchPhrase("titleWithStopwords", q) boost 2 slop 10
            ) tieBreaker 0 boost 3, // No tie breaker as it would be pointless in this case
            E.nestedQuery("contributors") query (
              E.matchPhrase("contributors.displayName", q) slop 10
            ) boost 2,
            E.nestedQuery("descriptions") query (
              E.matchPhrase("descriptions.content", q) slop 100
            ) boost 1
          ) tieBreaker 0.2
        } filter {
          E.termFilter("distributionStatus.usable", true)
        }
      } limit page.count from page.offset
    } map toBookSearchResponse(q)

  override def similar(bookId: BookId, page: Page): Future[BookSimilarResponse] =
    client execute {
      searchIn("book") query {
        E.morelikeThisQuery("title", "descriptionContents") minTermFreq 1 minDocFreq 1 minWordLength 3 maxQueryTerms 12 ids bookId.value
      } filter {
        E.termFilter("distributionStatus.usable", true)
      } limit page.count from page.offset
    } map toBookSimilarResponse

  override def suggestions(q: String, count: Int): Future[BookSuggestionResponse] = client execute {
    searchIn("catalogue") suggestions (
      E.suggest using(E.completion) as "autoComplete" on q from "autoComplete" size count
    ) filter {
      E.termFilter("distributionStatus.usable", true)
    } limit 0 // We don't want search results, only suggestions
  } map toSuggestionResponse
}
