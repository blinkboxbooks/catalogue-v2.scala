package com.blinkbox.books.catalogue.common.search

import com.blinkbox.books.catalogue.common.Events.{Book => EventBook, BookPrice => EventBookPrice, Undistribute => EventUndistribute}
import com.blinkbox.books.catalogue.common.Json
import com.blinkbox.books.catalogue.common.{DistributeContent, ElasticsearchConfig, IndexEntities => idx}
import com.blinkbox.books.elasticsearch.client.{ElasticClient => BBBElasticClient, ElasticClientApi}
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.mappings.FieldType._
import com.sksamuel.elastic4s.source.DocumentSource
import org.elasticsearch.index.VersionType
import org.json4s.jackson.Serialization
import scala.concurrent.{ExecutionContext, Future}
import com.sksamuel.elastic4s._

sealed trait BulkItemResponse
case class Successful(docId: String) extends BulkItemResponse
case class Failure(docId: String, cause: Option[Throwable] = None) extends BulkItemResponse

case class SingleResponse(docId: String)

trait Indexer {
  def index(content: DistributeContent): Future[SingleResponse]
  def index(contents: Iterable[DistributeContent]): Future[Iterable[BulkItemResponse]]
}

class HttpEsIndexer(config: ElasticsearchConfig, client: BBBElasticClient)(implicit ec: ExecutionContext) extends Indexer{

  import ElasticClientApi._
  import com.sksamuel.elastic4s.ElasticDsl.{bulk, index => esIndex}
  import Json._

  case class BookJsonSource(book: EventBook) extends DocumentSource {
    def json = Serialization.write(idx.Book.fromMessage(book))
  }

  case class UndistributeJsonSource(undistribute: EventUndistribute) extends DocumentSource {
    def json = Serialization.write(idx.Undistribute.fromMessage(undistribute))
  }

  case class BookPriceJsonSource(bookPrice: EventBookPrice) extends DocumentSource {
    def json = Serialization.write(idx.BookPrice.fromMessage(bookPrice))
  }

  trait IndexableContent[T <: DistributeContent] {
    type Out <: BulkCompatibleDefinition
    def definition(content: T): Out
  }

  implicit object BookIndexableContent extends IndexableContent[EventBook] {
    type Out = IndexDefinition

    override def definition(content: EventBook): Out =
      esIndex
        .into(s"${config.indexName}/book")
        .doc(BookJsonSource(content))
        .id(content.isbn)
        .versionType(VersionType.EXTERNAL)
        .version(content.sequenceNumber)
  }

  implicit object UndistributeIndexableContent extends IndexableContent[EventUndistribute] {
    type Out = IndexDefinition

    override def definition(content: EventUndistribute): Out =
      esIndex
        .into(s"${config.indexName}/distribution-status")
        .doc(UndistributeJsonSource(content))
        .id(content.isbn)
        .versionType(VersionType.EXTERNAL)
        .version(content.sequenceNumber)
        .parent(content.isbn)
  }

  implicit object BookPriceIndexableContent extends IndexableContent[EventBookPrice] {
    type Out = IndexDefinition

    override def definition(content: EventBookPrice): Out =
      esIndex
        .into(s"${config.indexName}/book-price")
        .doc(BookPriceJsonSource(content))
        .id(content.isbn)
        .parent(content.isbn)
  }

  override def index(content: DistributeContent): Future[SingleResponse] = {
    content match {
      case c: EventBook =>
        client.execute(indexDefinition(c))
          .flatMap { _ =>
            index(EventUndistribute(c.isbn, c.sequenceNumber, usable = true, reasons = List.empty))
          }
      case c: EventBookPrice =>
        client.execute(indexDefinition(c))
          .map(resp => SingleResponse(resp._id))
      case c: EventUndistribute =>
        client.execute(indexDefinition(c))
          .map(resp => SingleResponse(resp._id))
    }
  }

  override def index(contents: Iterable[DistributeContent]): Future[Iterable[BulkItemResponse]] =
    client.execute {
      bulk(
        contents.flatMap({
          case c: EventBook => List(
            indexDefinition(c),
            indexDefinition(EventUndistribute(c.isbn, c.sequenceNumber, usable = true, reasons = List.empty))
          )
          case c: EventBookPrice => List(indexDefinition(c))
          case c: EventUndistribute => List(indexDefinition(c))
        }).toList: _*
      )
    }.map { response =>
      response.items.map { item =>
        if (item.status.isFailure)
          Failure(item._id, Some(new RuntimeException(item.error.getOrElse(item.status.value))))
        else
          Successful(item._id)
      }
    }

  private def indexDefinition[T <: DistributeContent](content: T)(implicit ic: IndexableContent[T]): ic.Out =
    ic.definition(content)
}

case class Schema(config: ElasticsearchConfig) {
  def classification = "classification" nested(
    "realm" typed StringType analyzer KeywordAnalyzer,
    "id" typed StringType analyzer KeywordAnalyzer
  )

  def uris = "uris" inner(
    "type" typed StringType analyzer KeywordAnalyzer,
    "uri" typed StringType index "not_analyzed",
    "params" typed StringType index "not_analyzed"
  )

  def availability(`type`: String) = `type` inner(
    "available" typed BooleanType,
    "code" typed StringType analyzer KeywordAnalyzer,
    "extra" typed StringType
  )

  def otherText(name: String) = name nested(
    classification,
    "content" typed StringType analyzer "descriptionAnalyzer",
    "type" typed StringType analyzer KeywordAnalyzer,
    "author" typed StringType analyzer WhitespaceAnalyzer
  )

  def regions(name: String) = name nested(
    "GB" typed BooleanType nullValue false,
    "ROW" typed BooleanType nullValue false,
    "WORLD" typed BooleanType nullValue false
  )

  def catalogue = (create index config.indexName mappings (
    "book" as(
      "sequenceNumber" typed LongType,
      classification,
      "isbn" typed StringType analyzer KeywordAnalyzer,
      "format" inner(
        "marvinIncompatible" typed BooleanType,
        "epubType" typed StringType,
        "productForm" typed StringType
      ),
      "title" multi(
        "title" typed StringType copyTo("titleSimple", "spellcheck") analyzer SnowballAnalyzer,
        "titleSort" typed StringType index "not_analyzed"
      ),
      "titleSimple" typed StringType analyzer SimpleAnalyzer,
      "spellcheck" typed StringType analyzer SimpleAnalyzer,
      "subtitle" typed StringType analyzer SnowballAnalyzer,
      "contributors" nested (
        "role" typed StringType analyzer KeywordAnalyzer,
        "id" typed StringType analyzer KeywordAnalyzer,
        "displayName" typed StringType analyzer SimpleAnalyzer copyTo("spellcheck"),
        "sortName" typed StringType analyzer KeywordAnalyzer
        ),
      "availability" inner(
        availability("notificationType"),
        availability("publishingStatus"),
        availability("availabilityCode"),
        availability("productAvailability"),
        availability("blinkboxBooks")
      ),
      "dates" inner(
        "publish" typed DateType,
        "announce" typed DateType
      ),
      otherText("descriptions"),
      otherText("reviews"),
      "languages" typed StringType analyzer KeywordAnalyzer,
      "originalLanguages" typed StringType analyzer KeywordAnalyzer,
      regions("supplyRights"),
      regions("salesRights"),
      "publisher" typed StringType analyzer KeywordAnalyzer,
      "imprint" typed StringType,
      "prices" nested(
        "amount" typed DoubleType,
        "currency" typed StringType analyzer KeywordAnalyzer,
        "includeTax" typed BooleanType,
        "isAgency" typed BooleanType,
        "discountRate" typed IntegerType,
        "validFrom" typed DateType,
        "validUntil" typed DateType,
        regions("applicableRegions"),
        "tax" nested(
          "rate" typed StringType,
          "percent" typed DoubleType,
          "amount" typed DoubleType,
          "taxableAmount" typed DoubleType
        )
      ),
      "statistics" inner(
        "pages" typed IntegerType,
        "sentences" typed IntegerType,
        "words" typed IntegerType,
        "syllables" typed IntegerType,
        "polysyllables" typed IntegerType,
        "smog_grade" typed IntegerType, // TODO: Change to use camel case
        "adultThemes" typed BooleanType
      ),
      "subjects" nested(
        "type" typed StringType analyzer KeywordAnalyzer,
        "code" typed StringType analyzer KeywordAnalyzer,
        "main" typed BooleanType
      ),
      "series" nested(
        "title" typed StringType,
        "number" typed IntegerType
      ),
      "related" nested(
        classification,
        "relation" typed StringType analyzer KeywordAnalyzer,
        "isbn" typed StringType analyzer KeywordAnalyzer
      ),
      "media" inner(
        "images" inner(
          classification,
          uris,
          "width" typed IntegerType,
          "height" typed IntegerType,
          "size" typed IntegerType
        ),
        "epubs" inner(
          classification,
          uris,
          "keyFile" typed StringType index "not_analyzed",
          "wordCount" typed IntegerType,
          "size" typed IntegerType
        )
      ),
      "source" inner(
        "deliveredAt" typed DateType,
        "uri" typed StringType,
        "fileName" typed StringType,
        "contentType" typed StringType,
        "role" typed StringType,
        "username" typed StringType,
        "system" inner(
          "name" typed StringType,
          "version" typed StringType
        ),
        "processedAt" typed DateType
      ),
      // Calculated fields for specific search scenarios
      "descriptionContents" typed StringType,
      "autoComplete" typed CompletionType payloads true
    ) dynamic false,

    "book-price" as(
      "isbn" typed StringType analyzer KeywordAnalyzer,
      "price" typed DoubleType,
      "currency" typed StringType analyzer KeywordAnalyzer
    ) dynamic false parent "book",

    "distribution-status" as(
      "isbn" typed StringType analyzer KeywordAnalyzer,
      "usable" typed BooleanType,
      "reasons" typed StringType
    ) dynamic false parent "book"

  )).analysis(
    CustomAnalyzerDefinition("descriptionAnalyzer",
      StandardTokenizer,
      HtmlStripCharFilter,
      StandardTokenFilter,
      LowercaseTokenFilter,
      StopTokenFilter("descriptionStopWords"),
      SnowballTokenFilter("descriptionSnowball")
  ))
}
