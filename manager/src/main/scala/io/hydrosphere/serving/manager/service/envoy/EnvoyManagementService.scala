/*
package io.hydrosphere.serving.manager.service.envoy

import java.util.UUID

import io.hydrosphere.serving.manager.model.ModelServiceInstance
import io.hydrosphere.serving.manager.service.{RuntimeManagementService, ServingManagementService}
import io.hydrosphere.serving.manager.model.ModelService
import org.apache.logging.log4j.scala.Logging

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}


case class EnvoyClusterHost(
  url: String
)

case class EnvoyCluster(
  name: String,
  `type`: String,
  connect_timeout_ms: Long,
  lb_type: String,
  hosts: Option[Seq[EnvoyClusterHost]],
  service_name: Option[String],
  features: Option[String]
)

case class EnvoyClusterConfig(
  clusters: Seq[EnvoyCluster]
)

case class EnvoyRouteWeightedCluster(
  name: String,
  weight: Int
)

case class EnvoyRouteWeightedClusters(
  clusters: Seq[EnvoyRouteWeightedCluster]
)

case class EnvoyRoute(
  prefix: String,
  cluster: Option[String],
  timeout_ms: Option[Int],
  weighted_clusters: Option[EnvoyRouteWeightedClusters]
)

case class EnvoyRouteHost(
  name: String,
  domains: Seq[String],
  routes: Seq[EnvoyRoute]
)

case class EnvoyRouteConfig(
  virtual_hosts: Seq[EnvoyRouteHost]
)

case class EnvoyServiceTags(
  az: String,
  canary: String,
  load_balancing_weight: String
)

case class EnvoyServiceHost(
  ip_address: String,
  port: Int,
  tags: Option[Seq[EnvoyServiceTags]]
)

case class EnvoyServiceConfig(
  hosts: Seq[EnvoyServiceHost]
)

trait EnvoyManagementService {
  def clusters(fullName: String, containerId: String): Future[EnvoyClusterConfig]

  def services(serviceName: String): Future[EnvoyServiceConfig]

  def routes(configName: String, fullName: String, containerId: String): Future[EnvoyRouteConfig]
}

class EnvoyManagementServiceImpl(
  runtimeManagementService: RuntimeManagementService,
  servingManagementService: ServingManagementService
)(implicit val ex: ExecutionContext) extends EnvoyManagementService with Logging {

  private def fetchGatewayIfNeeded(modelService: ModelService): Future[Seq[ModelServiceInstance]] = {
    if (modelService.serviceId >= 0) {
      runtimeManagementService.instancesForService(runtimeManagementService.GATEWAY_ID)
    } else {
      Future.successful(Seq())
    }
  }

  private def findService(fullName: String): Future[ModelService] = {
    runtimeManagementService.serviceByFullName(fullName).map({
      case Some(ms) => ms
      case None => throw new IllegalArgumentException(s"Can't find service by fullName:$fullName")
    })
  }

  override def routes(configName: String, fullName: String, containerId: String): Future[EnvoyRouteConfig] = {
    findService(fullName).flatMap(modelService => {
      runtimeManagementService.allServices().flatMap(services => {
        servingManagementService.allApplications().flatMap(applications => {
          fetchGatewayIfNeeded(modelService).map(gatewayServiceInstances => {

            val routeHosts = mutable.MutableList[EnvoyRouteHost]()

            applications.foreach(s => {
              s.executionGraph.stages.indices.foreach(i=>{
                val serviceName=s"app${s.id}stage$i"

                val appStage=s.executionGraph.stages(i)
                val weights=EnvoyRouteWeightedClusters(
                  appStage.services.map(w => EnvoyRouteWeightedCluster(
                    //TODO optimize search
                    name = services.find(f => f.serviceId == w.serviceId).get.serviceName,
                    weight = w.weight
                  ))
                )

                routeHosts += EnvoyRouteHost(
                  name = serviceName,
                  domains = Seq(serviceName),
                  routes = Seq(EnvoyRoute(
                    prefix = "/",
                    cluster = None,
                    timeout_ms = Some(60000),
                    weighted_clusters = Some(weights)))
                )
              })
            })

            services.filter(s => s.serviceId != modelService.serviceId)
              .foreach(s => {
                routeHosts += EnvoyRouteHost(
                  name = s.serviceName.toLowerCase,
                  domains = Seq(s.serviceName.toLowerCase),
                  routes = Seq(EnvoyRoute("/", Some(s.serviceName), timeout_ms = Some(60000), None))
                )
              })

            gatewayServiceInstances.foreach(s => {
              routeHosts += EnvoyRouteHost(
                name = s.instanceId.toLowerCase,
                domains = Seq(s.instanceId.toLowerCase),
                routes = Seq(EnvoyRoute("/", Some(UUID.nameUUIDFromBytes(s.instanceId.getBytes()).toString), timeout_ms = Some(60000), None))
              )
            })

            routeHosts += EnvoyRouteHost(
              name = "all",
              domains = Seq("*"),
              routes = Seq(EnvoyRoute("/", Some(modelService.serviceName), timeout_ms = Some(60000), None))
            )

            EnvoyRouteConfig(
              virtual_hosts = routeHosts
            )
          })
        })
      })
    })
  }

  override def services(serviceName: String): Future[EnvoyServiceConfig] =
    runtimeManagementService.instancesForService(serviceName)
      .map(seq => {
        EnvoyServiceConfig(
          hosts = seq.map(s =>
            EnvoyServiceHost(
              ip_address = s.host,
              port = s.sidecarPort,
              tags = None
            )
          )
        )
      })

  override def clusters(fullName: String, containerId: String): Future[EnvoyClusterConfig] = {
    findService(fullName).flatMap(modelService => {
      runtimeManagementService.instancesForService(modelService.serviceId).flatMap(instancesSame => {
        runtimeManagementService.allServices().flatMap(services => {
          val containerInstance = instancesSame.find(p => p.serviceId == modelService.serviceId)
          if (containerInstance.isEmpty) {
            Future.successful(EnvoyClusterConfig(Seq()))
          } else {
            fetchGatewayIfNeeded(modelService).map(gatewayServiceInstances => {
              val clustres = mutable.MutableList[EnvoyCluster]()

              services.foreach(s => {
                if (s.serviceId == modelService.serviceId) {
                  clustres += EnvoyCluster(
                    features = None,
                    connect_timeout_ms = 500,
                    lb_type = "round_robin",
                    service_name = None,
                    name = s.serviceName,
                    `type` = "static",
                    hosts = Some(Seq(EnvoyClusterHost(s"tcp://127.0.0.1:${containerInstance.get.appPort}")))
                  )
                } else {
                  clustres += EnvoyCluster(
                    features = None,
                    connect_timeout_ms = 500,
                    lb_type = "round_robin",
                    service_name = Some(s.serviceName),
                    name = s.serviceName,
                    `type` = "sds",
                    hosts = None
                  )
                }
              })
              gatewayServiceInstances.foreach(s => {
                clustres += EnvoyCluster(
                  features = None,
                  connect_timeout_ms = 500,
                  lb_type = "round_robin",
                  service_name = None,
                  name = UUID.nameUUIDFromBytes(s.instanceId.getBytes).toString,
                  `type` = "static",
                  hosts = Some(getStaticHost(modelService, s, containerId))
                )
              })

              EnvoyClusterConfig(
                clusters = clustres
              )
            })
          }
        })
      })
    })
  }


  private def getStaticHost(runtime: ModelService, service: ModelServiceInstance, forNode: String): Seq[EnvoyClusterHost] = {
    val sameNode = service.instanceId == forNode
    val builder = new StringBuilder("tcp://")
    if (sameNode)
      builder.append("127.0.0.1")
    else
      builder.append(service.host)
    builder.append(":")
    if (sameNode)
      builder.append(service.appPort)
    else
      builder.append(service.sidecarPort)
    Seq(EnvoyClusterHost(builder.toString))
  }

}
*/



/*


package io.hydrosphere.serving.manager.service.envoy

import akka.actor.{ActorRef, ActorSystem, Props}
import envoy.api.v2.{DiscoveryRequest, DiscoveryResponse}
import io.grpc.stub.StreamObserver
import io.hydrosphere.serving.manager.service.envoy.xds._
import org.apache.logging.log4j.scala.Logging

trait EnvoyDiscoveryService {

  def subscribe(discoveryRequest: DiscoveryRequest, responseObserver: StreamObserver[DiscoveryResponse]): Unit

  def unsubscribe(responseObserver: StreamObserver[DiscoveryResponse]): Unit
}

class EnvoyDiscoveryServiceImpl
(
  implicit val system: ActorSystem
) extends EnvoyDiscoveryService with Logging {

  private val clusterDSActor: ActorRef = system.actorOf(Props(new ClusterDSActor))

  private val endpointDSActor: ActorRef = system.actorOf(Props(new EndpointDSActor))

  private val listenerDSActor: ActorRef = system.actorOf(Props(new ListenerDSActor))

  private val routeDSActor: ActorRef = system.actorOf(Props(new RouteDSActor))

  private val actors = Map(
    "type.googleapis.com/envoy.api.v2.Cluster" -> clusterDSActor,
    "type.googleapis.com/envoy.api.v2.ClusterLoadAssignment" -> endpointDSActor,
    "type.googleapis.com/envoy.api.v2.RouteConfiguration" -> routeDSActor,
    "type.googleapis.com/envoy.api.v2.Listener" -> listenerDSActor
  )

  clusterDSActor ! ClusterAdded(Seq("manager"))
  endpointDSActor ! RenewEndpoints(Seq(ClusterInfo(
    name = "manager",
    endpoints = Seq(ClusterEndpoint(
      host="192.168.90.68",
      port = 9091
    ))
  )))

  override def subscribe(discoveryRequest: DiscoveryRequest, responseObserver: StreamObserver[DiscoveryResponse]): Unit = {
    discoveryRequest.node.foreach(n => {
      actors.get(discoveryRequest.typeUrl)
        .fold(logger.info(s"Unknown typeUrl: $discoveryRequest"))(actor => {

          actor ! SubscribeMsg(
            node = n,
            resources = discoveryRequest.resourceNames,
            responseObserver = responseObserver
          )
        })
    })

  }

  override def unsubscribe(responseObserver: StreamObserver[DiscoveryResponse]): Unit = {
    val msg = UnsubscribeMsg(responseObserver)
    actors.values.foreach(actor => {
      actor ! msg
    })
  }
}


*/