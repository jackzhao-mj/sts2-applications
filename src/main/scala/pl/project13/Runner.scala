import akka.actor.{ Actor, ActorRef, DeadLetter }
import akka.actor.ActorSystem
import akka.actor.Props
import akka.dispatch.verification._
import scala.collection.mutable.Queue
import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Map
import scala.util.Random
import pl.project13.scala.akka.raft.example._
import pl.project13.scala.akka.raft.protocol._
import pl.project13.scala.akka.raft.example.protocol._
import pl.project13.scala.akka.raft._
import pl.project13.scala.akka.raft.model._
import runner.raftchecks._


class RaftMessageFingerprinter extends MessageFingerprinter {
  // TODO(cs): might be an easier way to do this. See ActorRef API.
  val refRegex = ".*raft-member-(\\d+).*".r

  def fingerprint(msg: Any) : MessageFingerprint = {
    def removeId(ref: ActorRef) : String = {
      ref.toString match {
        case refRegex(member) => return "raft-member-" + member
        case _ => return ref.toString
      }
    }
    val str = msg match {
      case RequestVote(term, ref, lastTerm, lastIdx) =>
        (("RequestVote", term, removeId(ref), lastTerm, lastIdx)).toString
      case LeaderIs(Some(ref), msg) =>
        ("LeaderIs", removeId(ref)).toString
      case m =>
        m.toString
    }
    return BasicFingerprint(str)
  }
}

class ClientMessageGenerator(raft_members: Seq[String]) extends MessageGenerator {
  val wordsUsedSoFar = new HashSet[String]
  val rand = new Random
  val destinations = new RandomizedHashSet[String]
  for (dst <- raft_members) {
    destinations.insert(dst)
  }

  def generateMessage(alive: RandomizedHashSet[String]) : Send = {
    val dst = destinations.getRandomElement()
    // TODO(cs): 10000 is a bit arbitrary, and this algorithm fails
    // disastrously as we start to approach 10000 Send events.
    var word = rand.nextInt(10000).toString
    while (wordsUsedSoFar contains word) {
      word = rand.nextInt(10000).toString
    }
    wordsUsedSoFar += word
    return Send(dst, () =>
      ClientMessage[AppendWord](Instrumenter().actorSystem.deadLetters, AppendWord(word)))
  }
}

object Main extends App {
  // TODO(cs): reset raftChecks as a restart hook.
  var raftChecks = new RaftChecks

  def invariant(seq: Seq[ExternalEvent], checkpoint: HashMap[String,Option[CheckpointReply]]) : Option[ViolationFingerprint] = {
    return Some(RaftViolation(new HashSet[String]))
    /*
    var livenessViolations = checkpoint.toSeq flatMap {
      case (k, None) => Some("Liveness:"+k)
      case _ => None
    }

    var normalReplies = checkpoint flatMap {
      case (k, None) => None
      case (k, Some(v)) => Some((k,v))
    }

    raftChecks.check(normalReplies) match {
      case Some(violations) =>
        println("Violations found! " + violations)
        return Some(RaftViolation(violations ++ livenessViolations))
      case None =>
        if (livenessViolations.isEmpty) {
          return None
        }
        println("Violations found! liveness" + livenessViolations)
        return Some(RaftViolation(new HashSet[String] ++ livenessViolations))
    }
    */
  }

  val members = (1 to 9) map { i => s"raft-member-$i" }

  val prefix = Array[ExternalEvent]() ++
    //Array[ExternalEvent](Start(() =>
    //  RaftClientActor.props(Instrumenter().actorSystem() / "raft-member-*"), "client")) ++
    members.map(member =>
      Start(() => Props.create(classOf[WordConcatRaftActor]), member)) ++
    members.map(member =>
      Send(member, () => {
        val clusterRefs = Instrumenter().actorMappings.filter({
            case (k,v) => k != "client" && !ActorTypes.systemActor(k)
        }).values
        ChangeConfiguration(ClusterConfiguration(clusterRefs))
      })) ++
    Array[ExternalEvent](
    WaitQuiescence,
    WaitTimers(1),
    Continue(10)
    //WaitQuiescence
    // Continue(500)
  )

  val weights = new FuzzerWeights(kill=0.01, send=0.3, wait_quiescence=0.1,
                                  wait_timers=0.3, partition=0.1, unpartition=0.1,
                                  continue=0.3)
  val messageGen = new ClientMessageGenerator(members)
  val fuzzer = new Fuzzer(500, weights, messageGen, prefix)

  var violationFound : ViolationFingerprint = null
  var traceFound : EventTrace = null
  while (violationFound == null) {
    val fuzzTest = fuzzer.generateFuzzTest()
    println("Trying: " + fuzzTest)

    val sched = new RandomScheduler(1, false, 30, false)
    sched.setInvariant(invariant)
    Instrumenter().scheduler = sched
    sched.explore(fuzzTest) match {
      case None =>
        println("Returned to main with events")
        sched.shutdown()
        println("shutdown successfully")
        raftChecks = new RaftChecks
      case Some((trace, violation)) =>
        println("Found a safety violation!")
        violationFound = violation
        traceFound = trace
        // Experiment.record_experiment("akka-raft", trace, violation)
        sched.shutdown()
    }
  }

  println("----------")
  println("Trying replay:")
  println("trace:")
  for (e <- traceFound) {
    println(e)
  }
  println("----------")
  val replayer = new ReplayScheduler(new RaftMessageFingerprinter, false, false)
  Instrumenter().scheduler = replayer
  // Very important! Need to update the actor refs recorded in the event
  // trace, since they are no longer valid for this new actor system.
  def updateActorRef(ref: ActorRef) : ActorRef = {
    val newRef = Instrumenter().actorSystem.actorFor("/user/" + ref.path.name)
    require(newRef.path.name != "deadLetters")
    return newRef
  }

  replayer.setEventMapper((e: Event) =>
    e match {
      case MsgSend(snd,rcv,ChangeConfiguration(config)) =>
        val updatedRefs = config.members.map(updateActorRef)
        val updatedConfig = ChangeConfiguration(ClusterConfiguration(updatedRefs))
        Some(MsgSend(snd,rcv,updatedConfig))
      case m =>
        Some(m)
    }
  )

  // Now do the replay.
  val events = replayer.replay(traceFound.filterCheckpointMessages())
  println("Done with replay")
  replayer.shutdown
  println("events:")
  for (e <- events) {
    println(e)
  }

  // Trying STSSched:
  // val minimizer : Minimizer = new LeftToRightRemoval(test_oracle)
  // val minimizer : Minimizer = new DeltaDebuggin(test_oracle)
  // val events = minimizer.minimize(trace)
}