import AssemblyKeys._ 

assemblySettings

/** Project */
name := "signal-collect"

version := "3.0.0-SNAPSHOT"

organization := "com.signalcollect"

scalaVersion := "2.10.3"

scalacOptions ++= Seq("-optimize", "-Yinline-warnings", "-feature", "-deprecation")

assembleArtifact in packageScala := true

excludedJars in assembly <<= (fullClasspath in assembly) map { cp => 
  cp filter {_.data.getName == "minlog-1.2.jar"}
}

test in assembly := {}

parallelExecution in Test := false

scalacOptions += "-deprecation"

EclipseKeys.createSrc := EclipseCreateSrc.Default + EclipseCreateSrc.Resource

EclipseKeys.withSource := true

jarName in assembly := "signal-collect-2.1-SNAPSHOT.jar"

/** Dependencies */
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.3.0-RC2",
  "com.typesafe.akka" %% "akka-remote" % "2.3.0-RC2",
  "com.typesafe.akka" %% "akka-cluster" % "2.3.0-RC2",
  "org.scala-lang" % "scala-library" % "2.10.3" % "compile",
  "com.esotericsoftware.kryo" % "kryo" % "2.21" % "compile",
  "ch.ethz.ganymed" % "ganymed-ssh2" % "build210"  % "compile",
  "commons-codec" % "commons-codec" % "1.7"  % "compile",
  "net.liftweb" % "lift-json_2.10" % "2.5-RC4" % "compile",
  "org.java-websocket" % "Java-WebSocket" % "1.3.0" % "compile",
  "junit" % "junit" % "4.8.2"  % "test",
  "org.specs2" % "classycle" % "1.4.1" % "test",
  "org.mockito" % "mockito-all" % "1.9.0"  % "test",
  "org.specs2" %% "specs2" % "2.3.3"  % "test",
  "org.scalacheck" %% "scalacheck" % "1.11.0" % "test",
  "org.scalatest" %% "scalatest" % "2.0.1-SNAP" % "test",
  "org.easymock" % "easymock" % "3.2" % "test"
)



resolvers += "Scala-Tools Repository" at "https://oss.sonatype.org/content/groups/scala-tools/"

resolvers += "Sonatype Snapshots Repository" at "https://oss.sonatype.org/content/repositories/snapshots/"

resolvers += "Sonatype Releases Repository" at "https://oss.sonatype.org/content/repositories/releases/"
