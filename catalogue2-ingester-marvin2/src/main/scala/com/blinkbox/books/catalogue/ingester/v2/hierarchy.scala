package com.blinkbox.books.catalogue.ingester.v2

import com.blinkbox.books.elasticsearch.client.ElasticClientApi
import java.util.concurrent.{Executors, TimeUnit}
import akka.actor.Status.Success
import akka.actor.SupervisorStrategy.Restart
import akka.actor._
import akka.util.Timeout
import com.blinkbox.books.catalogue.common.{ElasticFactory, ElasticsearchConfig, Json}
import com.blinkbox.books.catalogue.common.search.{HttpEsIndexer, Schema}
import com.blinkbox.books.catalogue.ingester.v2.Main._
import com.blinkbox.books.catalogue.ingester.v2.messaging.MessageHandler
import com.blinkbox.books.catalogue.ingester.v2.parser.JsonV2IngestionParser
import com.blinkbox.books.logging.DiagnosticExecutionContext
import com.blinkbox.books.messaging._
import com.blinkbox.books.rabbitmq.RabbitMqConfirmedPublisher.PublisherConfiguration
import com.blinkbox.books.rabbitmq.RabbitMqConsumer.QueueConfiguration
import com.blinkbox.books.rabbitmq.{RabbitMq, RabbitMqConfig, RabbitMqConfirmedPublisher, RabbitMqConsumer}
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object Hierarchy {
  def start(actorSystem: ActorSystem): ActorRef =
    actorSystem.actorOf(Props(new IngesterSupervisor), "catalogue-ingester-supervisor")
}

class IngesterSupervisor extends Actor with StrictLogging {
  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1.minute) {
      case _: Exception => Restart
    }

  override def receive: Receive = {
    context.actorOf(Props(new MessagingSupervisor), "messaging-supervisor")
    started()
  }

  private def started(): Receive = {
    case Children => sender ! context.children.toList.map(_.path.toString)
  }
}

class MessagingSupervisor extends Actor with StrictLogging {
  import scala.concurrent.duration._

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1.minute) {
      case _: Exception => Restart
    }

  override def receive: Receive = {
    startMessaging()
    started()
  }

  private def started(): Receive = {
    case Children =>
      sender ! context.children.toList.map(_.path.toString);
    case Success(_) =>
      // Do nothing; this is the expected behaviour.
    case msg =>
      logger.warn(s"Unknown message [$msg]")
  }

  private def startMessaging(): Unit = {
    import ElasticClientApi._
    import Json._

    implicit val msgExecutionCtx = DiagnosticExecutionContext(context.dispatcher)
    implicit val apiTimeout = Timeout(config.getDuration("messageListener.actorTimeout", TimeUnit.SECONDS).seconds)

    val rabbitmqConfig = RabbitMqConfig(config)
    val consumerConnection = RabbitMq.reliableConnection(rabbitmqConfig)
    val publisherConnection = RabbitMq.recoveredConnection(rabbitmqConfig)

    val errorsPublisher = context.actorOf(
      Props(new RabbitMqConfirmedPublisher(
        connection = publisherConnection,
        config = PublisherConfiguration(
          config.getConfig("messageListener.distributor.book.errors")))))

    val errorHandler = new ActorErrorHandler(errorsPublisher)
    val indexingEc = DiagnosticExecutionContext(ExecutionContext.fromExecutor(Executors.newCachedThreadPool))
    val searchConfig = ElasticsearchConfig(config)
    val httpEsClient = ElasticFactory.http(searchConfig)
    val indexer = new HttpEsIndexer(searchConfig, httpEsClient)(indexingEc)

    val messageConsumer = context.actorOf(
      Props(new RabbitMqConsumer(
        channel = consumerConnection.createChannel(),
        consumerTag = "ingester-books-consumer-v2",
        output = context.actorOf(
          Props(new MessageHandler(
            errorHandler = errorHandler,
            retryInterval = 10.seconds,
            indexer = indexer,
            messageParser = new JsonV2IngestionParser))),
        queueConfig = QueueConfiguration(
          config.getConfig("messageListener.distributor.book.input")))), name = "V2-Message-Consumer")

    httpEsClient.execute(Schema(searchConfig).catalogue).onComplete {
      case _ =>
        messageConsumer ! RabbitMqConsumer.Init
    }
  }
}
