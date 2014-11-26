package com.blinkbox.books.catalogue.ingester.v2

import akka.actor.ActorSystem
import com.blinkbox.books.config.Configuration

/**
 * Created by alinp on 20/11/14.
 */
object Main extends App with Configuration{
  private val actorSystem = ActorSystem("catalogue-ingester", config)
  private val supervisor = Hierarchy.start(actorSystem)

  sys.addShutdownHook{
    actorSystem.shutdown()
  }
}
