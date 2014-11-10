package com.blinkbox.books.catalogue.ingester

import java.util.concurrent.TimeUnit
import akka.actor.Status.Success
import akka.actor.SupervisorStrategy.Restart
import akka.actor._
import akka.util.Timeout
import com.blinkbox.books.catalogue.ingester.Main._
import com.blinkbox.books.catalogue.ingester.index.EsSearch
import com.blinkbox.books.catalogue.ingester.messaging.{V2MessageHandler, V1MessageHandler}
import com.blinkbox.books.catalogue.ingester.xml.{XmlV1IngestionParser, JsonV2IngestionParser}
import com.blinkbox.books.logging.DiagnosticExecutionContext
import com.blinkbox.books.messaging._
import com.blinkbox.books.rabbitmq.RabbitMqConfirmedPublisher.PublisherConfiguration
import com.blinkbox.books.rabbitmq.RabbitMqConsumer.QueueConfiguration
import com.blinkbox.books.rabbitmq.{RabbitMqConsumer, RabbitMqConfirmedPublisher, RabbitMq, RabbitMqConfig}
import com.typesafe.scalalogging.slf4j.StrictLogging
import org.json4s.JsonAST.JObject
import scala.concurrent.duration._

object Hierarchy {
  def start(actorSystem: ActorSystem): ActorRef =
    actorSystem.actorOf(Props(new IngesterSupervisor), "catalogue-ingester-supervisor")

  def startTestScheduler(actorSystem: ActorSystem): Unit = {
    implicit val msgExecutionCtx = DiagnosticExecutionContext(actorSystem.dispatcher)
    val rabbitmqConfig = RabbitMqConfig(config)
    val publisherConnection = RabbitMq.recoveredConnection(rabbitmqConfig)

    val messagePublisher = actorSystem.actorOf(
      Props(new RabbitMqConfirmedPublisher(
        connection = publisherConnection,
        config = PublisherConfiguration(
          config.getConfig("messageListener.distributor.book.output")))))
    implicit object JObjectJson extends JsonEventBody[JObject] {
      val jsonMediaType = MediaType("application/vnd.blinkbox.books.ingestion.book.metadata.v2+json")
    }
    val json = Event.json(EventHeader("application/vnd.blinkbox.books.ingestion.book.metadata.v2+json"), jvalue)
    actorSystem.scheduler.schedule(0.milliseconds, 5.seconds, new Runnable {
      override def run(): Unit = {
        for(i <- 0 to 0) {
          messagePublisher ! json
        }
      }
    })
  }
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

    val v1messageConsumer = context.actorOf(
      Props(new RabbitMqConsumer(
        channel = consumerConnection.createChannel(),
        consumerTag = "ingester-books-consumer-v1",
        output = context.actorOf(
          Props(new V1MessageHandler(
            errorHandler = errorHandler,
            retryInterval = 10.seconds,
            search = new EsSearch(config),
            messageParser = new XmlV1IngestionParser))),
        queueConfig = QueueConfiguration(
          config.getConfig("messageListener.distributor.book.inputv1")))))

    val v2messageConsumer = context.actorOf(
      Props(new RabbitMqConsumer(
        channel = consumerConnection.createChannel(),
        consumerTag = "ingester-books-consumer-v2",
        output = context.actorOf(
          Props(new V2MessageHandler(
            errorHandler = new ActorErrorHandler(errorsPublisher),
            retryInterval = 10.seconds,
            search = new EsSearch(config),
            messageParser = new JsonV2IngestionParser))),
        queueConfig = QueueConfiguration(
          config.getConfig("messageListener.distributor.book.inputv2")))))

    v1messageConsumer ! RabbitMqConsumer.Init
    v2messageConsumer ! RabbitMqConsumer.Init
    started()
  }

  private def started(): Receive = {
    case Children =>
      sender ! context.children.toList.map(_.path.toString);
    case Success(_) =>
      ()
    case msg =>
      logger.warn(s"Unknown message [$msg]")
  }
}