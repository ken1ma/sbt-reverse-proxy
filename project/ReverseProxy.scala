import scala.xml.XML

import scala.collection.mutable.Buffer

import java.io._
import java.net._
import io.netty.bootstrap._
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.handler.logging._

/**
 * This SBT task starts/stops a reverse proxy within the SBT process.
 */
object ReverseProxy {
	var bossGroup: EventLoopGroup = null
	var workerGroup: EventLoopGroup = null
	var serverCh: Channel = null

	def start(serverPort: Int, confFile: String, log: sbt.Logger) = {
		if (serverCh == null) {
			log.info("starting the proxy")

			case class ProxyPass(path: String, urlText: String) {
				val url = new URL(urlText)
				val destHost = url.getHost
				val destPort = url.getPort match {
					case -1 => 80 // port is not specified (default port)
					case port => port
				}
				val destPath = url.getPath
			}
			val conf = { // 設定ファイル読込み
				val in = new FileInputStream(confFile)
				try {
					(XML.load(in) \ "pass").map { case node =>
						val path = (node \ "@path").head.text // 設定ファイル内で、正規表現を許可する記述にしてもよい
						val url = (node \ "@url").head.text
						ProxyPass(path, url)
					}
				} finally in.close
			}.sortBy(_.path.length).reverse
			for (pp <- conf) {
				import pp._
				println(s"  $path -> $destHost:$destPort$destPath")
			}

			bossGroup = new NioEventLoopGroup(1)
			workerGroup = new NioEventLoopGroup
			def closeOnFlush(ch: Channel) {
				if (ch.isActive)
					ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
			}

			class Proxy2ServerHandler(inboundCh: Channel) extends ChannelInboundHandlerAdapter {
				override def channelActive(ctx: ChannelHandlerContext) {
					ctx.read
					ctx.write(Unpooled.EMPTY_BUFFER)
				}
				override def channelRead(ctx: ChannelHandlerContext, msg: Object) {
					inboundCh.writeAndFlush(msg).addListener(new ChannelFutureListener {
						override def operationComplete(future: ChannelFuture) {
							if (future.isSuccess)
								ctx.channel.read
							else
								future.channel.close
						}
					})
				}
				override def channelInactive(ctx: ChannelHandlerContext) {
					closeOnFlush(inboundCh)
				}
				override def exceptionCaught(ctx: ChannelHandlerContext, ex: Throwable) {
					ex.printStackTrace
					closeOnFlush(ctx.channel)
				}
			}

			class Client2ProxyHandler extends ChannelInboundHandlerAdapter {
				/**
				 * In order to support persistent connections,
				 * ( http://tools.ietf.org/html/rfc7230#section-6.3 ),
				 * This handler is in one of the following states:
				 * <ol>
				 *   <li>Waiting for a request start line
				 *   <li>Buffering request headers
				 *   <li>Waiting for the end of a request body
				 * </ol>
				 */
				object State extends Enumeration {
					type State = Value
					val WAIT_START_LINE, WAIT_HEADERS, WAIT_BODY = Value
				}
				import State._
				class Request {
					var state = WAIT_START_LINE
					val startLine = Buffer[Byte]()
					val headers = Buffer[Byte]()
					var contentLength: Option[Int] = None // the request body exists only when CONTENT-LENGTH header exists
				}

				var proxyPass: ProxyPass = _
				var outboundCh: Channel = _
				override def channelActive(ctx: ChannelHandlerContext) {
					ctx.channel.read // read the first chunk
				}
				override def channelRead(ctx: ChannelHandlerContext, msg: Object) {
					def sendToServer {
						outboundCh.writeAndFlush(msg).addListener(new ChannelFutureListener {
							override def operationComplete(future: ChannelFuture) {
								if (future.isSuccess)
									ctx.channel.read // read the next chunk
								else
									future.channel.close
							}
						})
					}
					if (proxyPass == null) {
						msg match {
							case byteBuf: ByteBuf =>
								def takeWhile(predicate: (Byte) => Boolean, start: Int): (Seq[Byte], Int) = {
									if (start >= byteBuf.capacity)
										(Nil, start)
									else {
										val b = byteBuf.getByte(start)
										if (predicate(b)) {
											val next = takeWhile(predicate, start + 1)
											(b +: next._1, next._2)
										} else
											(Nil, start)
									}
								}
								val (methodBytes, sepStart) = takeWhile(_ != ' ', 0)
								val (_, pathStart) = takeWhile(_ == ' ', sepStart)
								val (pathBytes, _) = takeWhile(_ != ' ', pathStart)

								val method = new String(methodBytes.toArray, "UTF-8")
								val path = new String(pathBytes.toArray, "UTF-8")
								log.debug(s"$method $path")

								proxyPass = conf.filter(pp => path.startsWith(pp.path)).headOption match {
									case Some(pp) => pp
									case None => throw new Exception(s"no match: path = $path")
								}
								val destHost = proxyPass.destHost
								val destPort = proxyPass.destPort
								// TODO: use destPath too

								val b = new Bootstrap()
										.group(ctx.channel.eventLoop)
										.channel(ctx.channel.getClass)
										.handler(new Proxy2ServerHandler(ctx.channel))
										.option[java.lang.Boolean](ChannelOption.AUTO_READ, false)
								val f = b.connect(destHost, destPort)
								outboundCh = f.channel
								f.addListener(new ChannelFutureListener {
									override def operationComplete(future: ChannelFuture) {
										if (future.isSuccess) {
											sendToServer
											ctx.channel.read // read the first chunk

										} else {
											val detail = firstNonNull(future.cause.getMessage, future.cause.getClass.getName)
											log.error(s"failed to connect to $destHost:$destPort${if (detail != null) s" ($detail)"}")
											ctx.channel.close // connection attempt has failed
										}
									}
								})
						}

					} else if (outboundCh.isActive)
						sendToServer
				}
				override def channelInactive(ctx: ChannelHandlerContext) {
					if (outboundCh != null)
						closeOnFlush(outboundCh)
				}
				override def exceptionCaught(ctx: ChannelHandlerContext, ex: Throwable) {
					ex.printStackTrace
					closeOnFlush(ctx.channel)
				}
			}

			class Client2ProxyInitializer extends ChannelInitializer[SocketChannel] {
				override def initChannel(ch: SocketChannel) {
					ch.pipeline().addLast(//new LoggingHandler(LogLevel.INFO),
							new Client2ProxyHandler)
				}
			}

			serverCh = new ServerBootstrap()
					.group(bossGroup, workerGroup)
					.channel(classOf[NioServerSocketChannel])
					//.handler(new LoggingHandler(LogLevel.INFO))
					.childHandler(new Client2ProxyInitializer)
					.childOption[java.lang.Boolean](ChannelOption.AUTO_READ, false)
					.bind(serverPort).sync.channel

		} else
			log.info("the proxy is already running")
	}

	def stop(log: sbt.Logger) {
		if (bossGroup != null || workerGroup != null || serverCh != null) {
			log.info("stopping the proxy")

			if (bossGroup != null) {
				bossGroup.shutdownGracefully
				bossGroup = null
			}

			if (workerGroup != null) {
				workerGroup.shutdownGracefully
				workerGroup = null
			}

			if (serverCh != null) {
				serverCh.closeFuture.sync
				serverCh = null
			}

		} else
			log.info("the proxy is not running")
	}

	/** @return the first non-null argument, or null if none of them results in non-null */
	def firstNonNull(funcs: (() => Any)*): Any = {
		funcs.foreach { func =>
			try {
				val value = func()
				if (value != null)
					return value
			} catch {
				case ex: NullPointerException => // ignore NPE when evaluating arguments
			}
		}
		null
	}
}
