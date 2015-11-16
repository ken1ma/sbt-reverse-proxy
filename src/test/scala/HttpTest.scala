import java.io._
import java.net._

import org.scalatest.FunSuite

class HttpTest extends FunSuite with HttpTestServer {
	implicit class InputStreamOps(in: InputStream) {
		def readAndClose = {
			val reader = new InputStreamReader(in, "UTF-8")
			try {
				val sb = new StringBuilder
				val buf = new Array[Char](8192)
				Iterator.continually(reader.read(buf)).takeWhile(_ != -1).foreach(len => sb.appendAll(buf, 0, len))
				sb.toString

			} finally {
				reader.close
			}
		}
	}

	test("/") {
		assertResult("<p>global: GET /") {
			new URL("http://localhost:8919/").openConnection.getInputStream.readAndClose.trim
		}
	}

	test("/foo") {
		assertResult("<p>global: GET /foo") {
			new URL("http://localhost:8919/foo").openConnection.getInputStream.readAndClose.trim
		}
	}

	test("/master/") {
		assertResult("<p>master: GET /master/") {
			new URL("http://localhost:8919/master/").openConnection.getInputStream.readAndClose.trim
		}
	}

	test("/master/bar") {
		assertResult("<p>master: GET /master/bar") {
			new URL("http://localhost:8919/master/bar").openConnection.getInputStream.readAndClose.trim
		}
	}
}

import org.scalatest.{Suite, SuiteMixin}

trait HttpTestServer extends SuiteMixin { this: Suite =>
	import javax.servlet.http._
	import org.eclipse.jetty.server._
	import org.eclipse.jetty.server.handler.AbstractHandler

	abstract override def withFixture(test: NoArgTest) = {
		val globalServer = new Server(50000)
		globalServer.setHandler(new AbstractHandler {
			override def handle(target: String, baseReq: Request, req: HttpServletRequest, resp: HttpServletResponse) {
				resp.setContentType("text/html; charset=utf-8")
				resp.setStatus(HttpServletResponse.SC_OK)
				resp.getWriter().println(s"<p>global: ${req.getMethod} $target")
				baseReq.setHandled(true)
			}
		})
		globalServer.start

		val masterServer = new Server(50010)
		masterServer.setHandler(new AbstractHandler {
			override def handle(target: String, baseReq: Request, req: HttpServletRequest, resp: HttpServletResponse) {
				resp.setContentType("text/html; charset=utf-8")
				resp.setStatus(HttpServletResponse.SC_OK)
				resp.getWriter().println(s"<p>master: ${req.getMethod} $target")
				baseReq.setHandled(true)
			}
		})
		masterServer.start

		try super.withFixture(test)
		finally {
			globalServer.stop
			masterServer.stop
		}
	}
}
