package com.blinkbox.books.agora.catalogue.book

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import org.mockito.Matchers._
import org.mockito.Mockito._
import com.sksamuel.elastic4s.ElasticClient
import org.scalatest.time.{Millis, Span}
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.{Future, Await}
import com.blinkbox.books.catalogue.common.Events.Book
import com.blinkbox.books.catalogue.common._
import com.blinkbox.books.catalogue.common.e2e.E2ESpec
import com.blinkbox.books.catalogue.common.BookFixtures.simpleBook
import org.joda.time.DateTime
import org.scalatest.BeforeAndAfterAll
import scala.concurrent.duration._
import org.scalatest.Suite
import scala.concurrent.duration.Duration

@RunWith(classOf[JUnitRunner])
class ElasticBookDaoTest extends FlatSpec with E2ESpec with Matchers with ScalaFutures with BeforeAndAfterAll {
  override implicit val e2eExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
  override implicit def patienceConfig = PatienceConfig(timeout = Span(5000, Millis), interval = Span(500, Millis))

  val dao = new ElasticBookDao(esClient, "catalogue/book")

  private def createBook(id: Int): Book = simpleBook.copy(
    `$schema` = None,
    isbn=id.toString,
    title=id.toString,
    dates=Some(Dates(Some(new DateTime(id)), None)),
    prices=List(simpleBook.prices.head.copy(amount=id))
  )

  val books = List.range(1, 4).map(createBook(_))
  val contributor = books(0).contributors.head.id
  val cobblers = "cobblers"
  val sortField = "title"
  val count = 50

  def catalogueIndex = e2e createIndex catalogue
  def populateIndex = catalogueIndex index(books.toSeq: _*)

  override def beforeAll(): Unit = {
    super.beforeAll()
    Await.ready(populateIndex.state, 10.seconds)
  }

  "The DAO" should "find an indexed book by ISBN" in {
    whenReady(dao.getBookByIsbn("1")) { result =>
      result should equal(Some(books(0)))
    }
  }
  
  it should "return None for an unknown book" in {
    whenReady(dao.getBookByIsbn(cobblers)) { result =>
      result should equal(None)
    }
  }
  
  it should "find multiple books by ISBN" in {
    whenReady(dao.getBooks(books.map(_.isbn))) { result =>
      result should equal(books)
    }
  }

  it should "omit unknown books from a bulk request" in {
    whenReady(dao.getBooks(List("1", cobblers, "2", cobblers, "3"))) { result =>
      result should equal(books)
    }
  }
  
  it should "find books for a given contributor (with default sort order)" in {
    whenReady(dao.getBooksByContributor(contributor, None, None, 0, count, sortField, false)) { result =>
      result should equal(BookList(books, 3))
    }
  }
  
  it should "return an empty results set for an unknown contributor" in {
    whenReady(dao.getBooksByContributor("cobblers", None, None, 0, count, sortField, true)) { result =>
      result should equal(BookList(List(), 0))
    }
  }
  
  it should "limit the number of results to the specified count" in {
     whenReady(dao.getBooksByContributor(contributor, None, None, 0, 1, sortField, false)) { result =>
      result should equal(BookList(List(books(0)), 3))
    }
  }
  
  it should "start the results at the given offset" in {
    whenReady(dao.getBooksByContributor(contributor, None, None, 1, count, sortField, false)) { result =>
      result should equal(BookList(books.drop(1), 3))
    }
  }
  
  it should "return books in reverse order with ascending parameter specified" in {
    whenReady(dao.getBooksByContributor(contributor, None, None, 0, count, sortField, true)) { result =>
      result should equal(BookList(books.reverse, 3))
    }
  }
  
  it should "sort books by publication date" in {
    whenReady(dao.getBooksByContributor(contributor, None, None, 0, count, "publication_date", true)) { result =>
      result should equal(BookList(books.reverse, 3))
    }
  }

  it should "sort books by price" in {
    whenReady(dao.getBooksByContributor(contributor, None, None, 0, count, "price", true)) { result =>
      result should equal(BookList(books.reverse, 3))
    }
  }

  it should "sort books by contributor name" in {
    whenReady(dao.getBooksByContributor(contributor, None, None, 0, count, "author", true)) { result =>
      result should equal(BookList(books, 3))
    }
  }

  it should "find books by a given contributor within the specified date-range" in {
    whenReady(dao.getBooksByContributor(contributor, Some(new DateTime(2)), Some(new DateTime(2)), 0, count, "publication_date", false)) { result =>
      result should equal(BookList(List(books(1)), 1))
    }
  }

  it should "fail for an invalid page offset" in {
    intercept[IllegalArgumentException] {
      dao.getBooksByContributor("contributor", None, None, -1, count, sortField, true)
    }
  }

  it should "fail for an invalid page size" in {
    intercept[IllegalArgumentException] {
      dao.getBooksByContributor("contributor", None, None, 0, 0, sortField, true)
    }
  }
  
  it should "fail for an invalid sort order" in {
    intercept[IllegalArgumentException] {
      dao.getBooksByContributor("contributor", None, None, 0, count, cobblers, true)
    }
  }
  
  it should "return related books for a given ISBN" in {
    whenReady(dao.getRelatedBooks("1", 0, count)) { result =>
      result should equal(BookList(books.drop(1), 2))
    }
  }
  
  it should "limit the number of related books to the given count" in {
    whenReady(dao.getRelatedBooks("1", 0, 1)) { result =>
      result should equal(BookList(List(books(1)), 2))
    }
  }
  
  it should "start the related books at the given offset" in {
    whenReady(dao.getRelatedBooks("1", 1, 1)) { result =>
      result should equal(BookList(List(books(2)), 2))
    }
  }
  
  it should "return an empty set of related books for an unknown ISBN" in {
    whenReady(dao.getRelatedBooks(cobblers, 0, count)) { result =>
      result should equal(BookList(List(), 0))
    }
  }
}