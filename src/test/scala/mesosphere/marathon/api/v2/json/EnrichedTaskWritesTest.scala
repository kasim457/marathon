package mesosphere.marathon
package api.v2.json

import mesosphere.UnitTest
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.core.appinfo.EnrichedTask
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.raml.AnyToRaml
import mesosphere.marathon.raml.TaskConversion._
import mesosphere.marathon.state.{AppDefinition, PathId, Timestamp}
import mesosphere.marathon.stream.Implicits._
import org.apache.mesos.{Protos => MesosProtos}

class EnrichedTaskWritesTest extends UnitTest {

  class Fixture {
    val time = Timestamp(1024)

    val runSpec = AppDefinition(id = PathId("/foo/bar"))
    val runSpecId = runSpec.id
    val hostName = "agent1.mesos"
    val agentId = "abcd-1234"
    val agentInfo = Instance.AgentInfo(hostName, Some(agentId), None, None, attributes = Seq.empty)

    val networkInfos = Seq(
      MesosProtos.NetworkInfo.newBuilder()
        .addIpAddresses(MesosProtos.NetworkInfo.IPAddress.newBuilder().setIpAddress("123.123.123.123"))
        .addIpAddresses(MesosProtos.NetworkInfo.IPAddress.newBuilder().setIpAddress("123.123.123.124"))
        .build()
    )

    val taskWithoutIp = {
      val instance = TestInstanceBuilder.newBuilder(runSpecId = runSpecId, version = time)
        .withAgentInfo(agentInfo)
        .addTaskStaging(since = time)
        .getInstance()
      EnrichedTask(instance.runSpecId, instance.appTask, agentInfo, Nil, Nil, None)
    }

    def mesosStatus(taskId: Task.Id) = {
      MesosProtos.TaskStatus.newBuilder()
        .setTaskId(taskId.mesosTaskId)
        .setState(MesosProtos.TaskState.TASK_STAGING)
        .setContainerStatus(
          MesosProtos.ContainerStatus.newBuilder().addAllNetworkInfos(networkInfos.asJava)
        ).build
    }

    val taskWithMultipleIPs = {
      val instanceId = Instance.Id.forRunSpec(PathId("/foo/bar"))
      val taskStatus = mesosStatus(Task.Id(instanceId))
      val networkInfo = NetworkInfo(hostName, hostPorts = Nil, ipAddresses = Nil).update(taskStatus)
      val instance = TestInstanceBuilder.newBuilder(runSpecId = runSpecId, version = time)
        .withAgentInfo(agentInfo)
        .addTaskWithBuilder().taskStaging(since = time)
        .withNetworkInfo(networkInfo)
        .build().getInstance()
      EnrichedTask(instance.runSpecId, instance.appTask, agentInfo, Nil, Nil, None)
    }
  }

  "Enriched Task Writes" should {
    "JSON serialization of a Task without IPs" in {
      val f = new Fixture()
      val json =
        s"""
        |{
        |  "appId": "${f.runSpecId}",
        |  "healthCheckResults" : [],
        |  "id": "${f.taskWithoutIp.task.taskId.idString}",
        |  "ipAddresses" : [],
        |  "host": "agent1.mesos",
        |  "state": "TASK_STAGING",
        |  "ports": [],
        |  "servicePorts" : [],
        |  "stagedAt": "1970-01-01T00:00:01.024Z",
        |  "version": "1970-01-01T00:00:01.024Z",
        |  "slaveId": "abcd-1234",
        |  "localVolumes" : []
        |}
      """.stripMargin
      JsonTestHelper.assertThatJsonOf(f.taskWithoutIp.toRaml).correspondsToJsonString(json)
    }

    "JSON serialization of a Task with multiple IPs" in {
      val f = new Fixture()
      val json =
        s"""
        |{
        |  "appId": "${f.runSpecId}",
        |  "healthCheckResults" : [],
        |  "id": "${f.taskWithMultipleIPs.task.taskId.idString}",
        |  "host": "agent1.mesos",
        |  "state": "TASK_STAGING",
        |  "ipAddresses": [
        |    {
        |      "ipAddress": "123.123.123.123",
        |      "protocol": "IPv4"
        |    },
        |    {
        |      "ipAddress": "123.123.123.124",
        |      "protocol": "IPv4"
        |    }
        |  ],
        |  "ports": [],
        |  "servicePorts" : [],
        |  "stagedAt": "1970-01-01T00:00:01.024Z",
        |  "version": "1970-01-01T00:00:01.024Z",
        |  "slaveId": "abcd-1234",
        |  "localVolumes" : []
        |}
      """.stripMargin
      JsonTestHelper.assertThatJsonOf(f.taskWithMultipleIPs.toRaml).correspondsToJsonString(json)
    }
  }
}
