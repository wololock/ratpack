/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.stream

import ratpack.test.internal.RatpackGroovyDslSpec

import java.util.concurrent.CountDownLatch

import static io.netty.handler.codec.http.HttpResponseStatus.OK
import static ratpack.stream.HttpResponseChunks.httpResponseChunks
import static ratpack.stream.ServerSentEvents.serverSentEvents
import static ratpack.stream.Streams.*

class ResponseStreamingSpec extends RatpackGroovyDslSpec {

  def "can send chunked response"() {
    given:
    handlers {
      handler {
        render httpResponseChunks(
          transform(
            publisher("This is a really long string that needs to be sent chunked".toList().collate(20))
          ) { new HttpResponseChunk(it.join("")) }
        )
      }
    }

    expect:
    def chunkedResponse = []
    Socket socket = new Socket(getAddress().host, getAddress().port)
    try {
      new OutputStreamWriter(socket.outputStream, "UTF-8").with {
        write("GET / HTTP/1.1\r\n")
        write("Connection: close\r\n")
        write("\r\n")
        flush()
      }

      InputStreamReader inputStreamReader = new InputStreamReader(socket.inputStream)
      BufferedReader bufferedReader = new BufferedReader(inputStreamReader)

      chunkedResponse << bufferedReader.readLine()
      def chunk
      while ((chunk = bufferedReader.readLine()) != null) {
        chunkedResponse << chunk
      }
    } finally {
      socket.close()
    }

    chunkedResponse == ['HTTP/1.1 200 OK', 'Transfer-Encoding: chunked', '', '14', 'This is a really lon', '14', 'g string that needs ', '12', 'to be sent chunked', '0', '']
  }

  def "can send server sent event"() {
    given:
    handlers {
      handler {
        render serverSentEvents(transform(publisher(1..3)) {
          new ServerSentEvent(it.toString(), "add", "Event $it".toString())
        })
      }
    }

    expect:
    def response = get()
    response.statusCode == OK.code()
    response.headers["Content-Type"] == "text/event-stream;charset=UTF-8"
    response.headers["Cache-Control"] == "no-cache, no-store, max-age=0, must-revalidate"
    response.headers["Pragma"] == "no-cache"
    response.body.text == "event: add\ndata: Event 1\nid: 1\n\nevent: add\ndata: Event 2\nid: 2\n\nevent: add\ndata: Event 3\nid: 3\n\n"
  }

  def "can cancel a stream when a client drops connection"() {
    def cancelLatch = new CountDownLatch(1)
    def sentLatch = new CountDownLatch(1)

    given:
    handlers {
      handler {
        render serverSentEvents(
          onCancel(
            wiretap(
              transform(
                publisher(1..1000), { new ServerSentEvent(it.toString(), "add", "Event $it".toString()) }
              )
            ) {
              sentLatch.countDown()
            }
          ) {
            cancelLatch.countDown()
          }
        )
      }
    }

    expect:
    URLConnection conn = getAddress().toURL().openConnection()
    conn.connect()
    InputStream is = conn.inputStream
    // wait for at least one event to be sent to the subscriber
    sentLatch.await()
    is.close()

    // when the connection is closed cancel should be called
    cancelLatch.await()
  }
}