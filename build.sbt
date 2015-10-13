name := "advr.proxy"

scalaVersion in ThisBuild := "2.11.7"

libraryDependencies in ThisBuild ++= Seq(
	"org.slf4j" % "slf4j-api" % "1.7.12",          // log api
	"ch.qos.logback" % "logback-classic" % "1.1.3",// log implementation
	"org.scalatest" % "scalatest_2.11" % "2.2.4" % "test",
	"org.eclipse.jetty" % "jetty-server" % "9.3.3.v20150827" % "test"
)

scalacOptions in ThisBuild ++= Seq("-unchecked", "-deprecation", "-feature")

lazy val `dev-proxy-start` = taskKey[Unit]("start the proxies for the development environment")
`dev-proxy-start` := ReverseProxy.start(8919, "project/ReverseProxy.xml", streams.value.log)
lazy val `dev-proxy-stop` = taskKey[Unit]("stop the proxies for the development environment")
`dev-proxy-stop` := ReverseProxy.stop(streams.value.log)
