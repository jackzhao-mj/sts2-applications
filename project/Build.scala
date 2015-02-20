import sbt._
import sbt.Keys._
import com.typesafe.sbt.SbtAspectj.{ Aspectj, aspectjSettings, useInstrumentedClasses }
import com.typesafe.sbt.SbtAspectj.AspectjKeys.inputs

object STS2Application extends Build {
  lazy val sets2app = Project(
    id = "randomSearch",
    base = file("."),
    settings = Defaults.defaultSettings ++ aspectjSettings ++ Seq(
      organization := "com.typesafe.sbt.aspectj",
      version := "0.1",
      scalaVersion := "2.11.0",
      libraryDependencies += "com.lambdaworks" % "jacks_2.11" % "2.3.3",
      libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.3.6",
      libraryDependencies += "com.typesafe.akka" %% "akka-cluster" % "2.3.6",
      libraryDependencies += "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.2",
      libraryDependencies += "com.assembla.scala-incubator" %% "graph-core" % "1.9.0",
      libraryDependencies += "com.assembla.scala-incubator" %% "graph-dot" % "1.9.0",
      libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0",
      libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.1.2",

      // add akka-actor as an aspectj input (find it in the update report)
      inputs in Aspectj <++= update map { report =>
        report.matching(moduleFilter(organization = "com.typesafe.akka", name = "akka-actor*"))
      },

      // replace the original akka-actor jar with the instrumented classes in runtime
      fullClasspath in Runtime <<= useInstrumentedClasses(Runtime)
    )
  )
}
