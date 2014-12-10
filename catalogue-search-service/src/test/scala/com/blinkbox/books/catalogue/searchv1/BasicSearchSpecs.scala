package com.blinkbox.books.catalogue.searchv1

import com.blinkbox.books.catalogue.searchv1.V1SearchService.{BookSearchResponse, Book}
import com.blinkbox.books.catalogue.common.BookFixtures
import org.scalatest.{FlatSpec, Matchers}
import spray.http.StatusCodes

class BasicSearchSpecs extends FlatSpec with Matchers with ApiSpecBase {

  def simpleBookResponse(q: String) = BookSearchResponse(q, Book("1234567890123", "A simple book", "Foo C. Bar" :: Nil) :: Nil, 1)
  def emptyBookResponse(q: String) = BookSearchResponse(q, Nil, 0)

  "The search API" should "retrieve empty result-sets from an empty index" in {
    catalogueIndex andAfter { _ =>
      Get("/catalogue/search/books?q=Foo") ~> routes ~> check {
        status should equal(StatusCodes.OK)
        responseAs[BookSearchResponse] should equal(emptyBookResponse("Foo"))
      }
    }
  }

  it should "retrieve a simple book if given a query that match in the title" in {
    catalogueIndex indexAndCheck BookFixtures.simpleBook andAfter { _ =>
      Get("/catalogue/search/books?q=simple") ~> routes ~> check {
        status should equal(StatusCodes.OK)
        responseAs[BookSearchResponse] should equal(simpleBookResponse("simple"))
      }
    }
  }

  it should "retrieve a simple book if given a query that match in the author" in {
    catalogueIndex indexAndCheck BookFixtures.simpleBook andAfter { _ =>
      Get("/catalogue/search/books?q=foo") ~> routes ~> check {
        status should equal(StatusCodes.OK)
        responseAs[BookSearchResponse] should equal(simpleBookResponse("foo"))
      }
    }
  }

  it should "retrieve a simple book if given a query that match in the content" in {
    catalogueIndex indexAndCheck BookFixtures.simpleBook andAfter { _ =>
      Get("/catalogue/search/books?q=description") ~> routes ~> check {
        status should equal(StatusCodes.OK)
        responseAs[BookSearchResponse] should equal(simpleBookResponse("description"))
      }
    }
  }

  it should "retrieve a simple book if given a query that matches the ISBN" in {
    e2e createIndex catalogue indexAndCheck BookFixtures.simpleBook andAfter { _ =>
      Get("/catalogue/search/books?q=1234567890123") ~> routes ~> check {
        status should equal(StatusCodes.OK)
        responseAs[BookSearchResponse] should equal(simpleBookResponse("1234567890123"))
      }
    }
  }

  it should "retrieve an empty result set if the given query do not match any field" in {
    catalogueIndex indexAndCheck BookFixtures.simpleBook andAfter { _ =>
      Get("/catalogue/search/books?q=foobar") ~> routes ~> check {
        status should equal(StatusCodes.OK)
        responseAs[BookSearchResponse] should equal(emptyBookResponse("foobar"))
      }
    }
  }

  it should "fail with a 400 (Bad Request) if the 'q' parameter is not provided" in {
    catalogueIndex andAfter { _ =>
      Get("/catalogue/search/books") ~> routes ~> check {
        status should equal(StatusCodes.BadRequest)
      }
    }
  }
}