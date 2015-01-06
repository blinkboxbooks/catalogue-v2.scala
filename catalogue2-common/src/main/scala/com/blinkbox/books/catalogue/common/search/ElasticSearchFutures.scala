package com.blinkbox.books.catalogue.common.search

import org.elasticsearch.client.transport.NoNodeAvailableException
import scala.concurrent.{ExecutionContext, Future}

trait ElasticSearchFutures {
  implicit class ElasticSearchRichFuture[T](future: Future[T])(implicit ec: ExecutionContext) {
    def recoverException: Future[T] = {
      future.transform(identity, {
        case e: NoNodeAvailableException => CommunicationException(e)
        case e => e
      })
    }
  }
}
