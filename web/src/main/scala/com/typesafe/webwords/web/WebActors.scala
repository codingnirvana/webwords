package com.typesafe.webwords.web

import scala.collection.mutable
import akka.actor.{ Index => _, _ }
import akka.http._
import com.typesafe.webwords.common._
import java.net.URL
import java.net.MalformedURLException
import javax.servlet.http.HttpServletResponse

// this is just here for testing a simple case.
class HelloActor extends Actor {
    override def receive = {
        case get: Get =>
            get OK "hello!"
        case request: RequestMethod =>
            request NotAllowed "unsupported request"
    }
}

// we send any paths we don't recognize to this one.
class Custom404Actor extends Actor {
    override def receive = {
        case request: RequestMethod =>
            // you could do something nicer ;-) this is just an example
            request NotFound "Nothing here!"
    }
}

// this actor handles the main page.
class WordsActor(config: WebWordsConfig) extends Actor {
    private val client = Actor.actorOf(new ClientActor(config))

    case class Finish(request: RequestMethod, url: String, index: Option[Index],
        cacheHit: Boolean, startTime: Long)

    private def handleFinish(finish: Finish) = {
        val elapsed = System.currentTimeMillis - finish.startTime
        finish match {
            case Finish(request, url, Some(index), cacheHit, startTime) =>
                val response = request.response
                response.setContentType("text/plain")
                response.setCharacterEncoding("utf-8")
                val writer = response.getWriter()
                writer.println("Meta")
                writer.println("=====")
                writer.println("Cache hit = " + cacheHit)
                writer.println("Time = " + elapsed + "ms")
                writer.println("")
                writer.println("Word Counts")
                writer.println("=====")
                for ((word, count) <- index.wordCounts) {
                    writer.println(word + "\t\t" + count)
                }
                writer.println("")
                writer.println("Links")
                writer.println("=====")
                for ((text, url) <- index.links) {
                    writer.println(text + "\t\t" + url)
                }
                request.OK("")
            case Finish(request, url, None, cacheHit, startTime) =>
                request.OK("Failed to index url in " + elapsed + "ms (try reloading)")
        }
    }

    private def handleGet(get: RequestMethod) = {
        val skipCache = Option(get.request.getParameter("skipCache")).getOrElse("false") == "true"
        val url = Option(get.request.getParameter("url")) flatMap { string =>
            try {
                Some(new URL(string))
            } catch {
                case e: MalformedURLException =>
                    None
            }
        }

        val response = get.response

        if (url.isDefined) {
            val startTime = System.currentTimeMillis
            val futureGotIndex = client ? GetIndex(url.get.toExternalForm, skipCache)

            futureGotIndex foreach { reply =>
                // now we're in another thread, so we just send ourselves
                // a message, don't touch actor state
                reply match {
                    case GotIndex(url, indexOption, cacheHit) =>
                        self ! Finish(get, url, indexOption, cacheHit, startTime)
                }
            }

            // we have to worry about timing out also.
            futureGotIndex onTimeout { _ =>
                // again in another thread - most methods on futures are in another thread!
                self ! Finish(get, url.get.toExternalForm, index = None, cacheHit = false, startTime = startTime)
            }
        } else {
            get.BadRequest("Invalid or missing url parameter")
        }
    }

    override def receive = {
        case get: Get =>
            handleGet(get)
        case request: RequestMethod =>
            request NotAllowed "unsupported request"
        case finish: Finish =>
            handleFinish(finish)
    }

    override def preStart = {
        client.start
    }

    override def postStop = {
        client.stop
    }
}

// This actor simply delegates to the real handlers.
// There are extra libraries such as Spray that make this less typing:
//   https://github.com/spray/spray/wiki
// but for this example, showing how you would do it manually.
class WebBootstrap(rootEndpoint: ActorRef, config: WebWordsConfig) extends Actor with Endpoint {
    private val handlers = Map(
        "/hello" -> Actor.actorOf[HelloActor],
        "/words" -> Actor.actorOf(new WordsActor(config)))

    private val custom404 = Actor.actorOf[Custom404Actor]

    // Caution: this callback does not run in the actor thread,
    // so has to be thread-safe. We keep it simple and only touch
    // immutable values so there's nothing to worry about.
    private val handlerFactory: PartialFunction[String, ActorRef] = {
        case path if handlers.contains(path) =>
            handlers(path)
        case "/" =>
            handlers("/words")
        case path: String =>
            custom404
    }

    override def receive = handleHttpRequest

    override def preStart = {
        // start up our handlers
        handlers.values foreach { _.start }
        custom404.start

        // register ourselves with the akka-http RootEndpoint actor.
        // In Akka 2.0, Endpoint.Attach takes a partial function,
        // in 1.2 it still takes two separate functions.
        // So in 2.0 this can just be Endpoint.Attach(handlerFactory)
        rootEndpoint ! Endpoint.Attach({
            path =>
                handlerFactory.isDefinedAt(path)
        }, {
            path =>
                handlerFactory(path)
        })
    }

    override def postStop = {
        handlers.values foreach { _.stop }
        custom404.stop
    }
}
