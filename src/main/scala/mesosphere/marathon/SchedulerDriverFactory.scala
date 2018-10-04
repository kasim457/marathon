package mesosphere.marathon

import javax.inject.Inject

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.typesafe.scalalogging.StrictLogging
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.core.base.CrashStrategy
import mesosphere.marathon.storage.repository.{ FrameworkIdRepository, InstanceRepository }
import org.apache.mesos.Protos.FrameworkID
import org.apache.mesos.{ Scheduler, SchedulerDriver }
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration.{ Duration, FiniteDuration }

trait SchedulerDriverFactory {
  def createDriver(): SchedulerDriver
}

class MesosSchedulerDriverFactory @Inject() (
  holder: MarathonSchedulerDriverHolder,
  config: MarathonConf,
  httpConfig: HttpConf,
  frameworkIdRepository: FrameworkIdRepository,
  instanceRepository: InstanceRepository,
  crashStrategy: CrashStrategy,
  scheduler: Scheduler)(implicit materializer: Materializer)

    extends SchedulerDriverFactory {

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  log.debug("using scheduler " + scheduler)

  /**
    * As a side effect, the corresponding driver is set in the [[MarathonSchedulerDriverHolder]].
    */
  override def createDriver(): SchedulerDriver = {
    val frameworkId: Option[FrameworkID] = MesosSchedulerDriverFactory.getFrameworkId(
      crashStrategy, config.zkTimeoutDuration, frameworkIdRepository, instanceRepository)
    val driver = MarathonSchedulerDriver.newDriver(config, httpConfig, scheduler, frameworkId)
    holder.driver = Some(driver)
    driver
  }
}

object MesosSchedulerDriverFactory extends StrictLogging {
  def getFrameworkId(
    crashStrategy: CrashStrategy,
    zkTimeout: FiniteDuration,
    frameworkIdRepository: FrameworkIdRepository,
    instanceRepository: InstanceRepository)(implicit mat: Materializer): Option[FrameworkID] = {

    def instancesAreDefined: Boolean = Await.result(instanceRepository.ids().runWith(Sink.headOption), zkTimeout).nonEmpty

    Await.result(frameworkIdRepository.get(), zkTimeout).map(_.toProto) match {
      case frameworkId @ Some(_) =>
        frameworkId
      case None if instancesAreDefined =>
        logger.error("Refusing to create a new Framework ID while there are existing instances.\n" +
          "Please see for an explanation of the issue, and how to recover: https://mesosphere.github.io/marathon/docs/framework-id.html")
        Await.result(crashStrategy.crash(), Duration.Inf)
        throw new RuntimeException("Refusing to allow creation of a new Framework ID")
      case None =>
        logger.warn("No frameworkId could be read and no instances are defined. This will result in a new frameworkId")
        None
    }
  }
}
