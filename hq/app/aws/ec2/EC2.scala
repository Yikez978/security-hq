package aws.ec2

import aws.Auth
import aws.AwsAsyncHandler.awsToScala
import aws.support.TrustedAdvisorSGOpenPorts
import cats.instances.map._
import cats.instances.set._
import cats.syntax.semigroup._
import com.amazonaws.auth.AWSCredentialsProviderChain
import com.amazonaws.regions.Regions
import com.amazonaws.services.ec2.model.{DescribeNetworkInterfacesRequest, DescribeNetworkInterfacesResult, Filter, NetworkInterface}
import com.amazonaws.services.ec2.{AmazonEC2Async, AmazonEC2AsyncClientBuilder}
import model._

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}


object EC2 {
  def client(auth: AWSCredentialsProviderChain, region: Regions): AmazonEC2Async = {
    AmazonEC2AsyncClientBuilder.standard()
      .withCredentials(auth)
      .withRegion(region)
      .build()
  }

  def client(awsAccount: AwsAccount, region: Regions): AmazonEC2Async = {
    val auth = Auth.credentialsProvider(awsAccount)
    client(auth, region)
  }

  /**
    * Given a Trusted Advisor Security Group open ports result,
    * makes EC2 calls in each region to look up the Network Interfaces
    * attached to each flagged Security Group.
    */
  def getSgsUsage(sgReport: TrustedAdvisorDetailsResult[SGOpenPortsDetail], awsAccount: AwsAccount)(implicit ec: ExecutionContext): Future[Map[String, Set[SGInUse]]] = {
    val allSgIds = TrustedAdvisorSGOpenPorts.sgIds(sgReport)
    val activeRegions = sgReport.flaggedResources.map(sgInfo => Regions.fromName(sgInfo.region)).distinct

    for {
      dnirs <- Future.traverse(activeRegions){ region =>
        getSgsUsageForRegion(allSgIds, client(awsAccount, region))
      }
    } yield {
      dnirs
        .map(parseDescribeNetworkInterfacesResults(_, allSgIds))
        .fold(Map.empty)(_ |+| _)
    }
  }

  private[ec2] def getSgsUsageForRegion(sgIds: List[String], client: AmazonEC2Async)(implicit ec: ExecutionContext): Future[DescribeNetworkInterfacesResult] = {
    val request = new DescribeNetworkInterfacesRequest()
        .withFilters(new Filter("group-id", sgIds.asJava))
    awsToScala(client.describeNetworkInterfacesAsync)(request)
  }

  private[ec2] def parseDescribeNetworkInterfacesResults(dnir: DescribeNetworkInterfacesResult, sgIds: List[String]): Map[String, Set[SGInUse]] = {
    dnir.getNetworkInterfaces.asScala.toSet
      .flatMap { (ni: NetworkInterface) =>
        val sgUse = parseNetworkInterface(ni)
        val matchingGroupIds = ni.getGroups.asScala.toList.filter(gi => sgIds.contains(gi.getGroupId))
        matchingGroupIds.map(_ -> sgUse)
      }
      .groupBy { case (sgId, _) => sgId.getGroupId }
      .mapValues { pairs =>
        pairs.map { case (_, sgUse) => sgUse }
      }
  }

  private[ec2] def parseNetworkInterface(ni: NetworkInterface): SGInUse = {
    val elb = Option(ni.getAttachment.getInstanceOwnerId).find(_ == "amazon-elb")
      .map(_ => ELB(ni.getDescription))
    val instance = Option(ni.getAttachment.getInstanceId).map(Instance)

    elb
      .orElse(instance)
      .getOrElse(
        UnknownUsage(ni.getDescription, ni.getNetworkInterfaceId)
      )
  }
}
