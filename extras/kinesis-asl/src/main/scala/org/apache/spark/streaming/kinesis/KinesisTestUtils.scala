/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.streaming.kinesis

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Random, Success, Try}

import com.amazonaws.auth.{AWSCredentials, DefaultAWSCredentialsProviderChain}
import com.amazonaws.regions.RegionUtils
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient
import com.amazonaws.services.dynamodbv2.document.DynamoDB
import com.amazonaws.services.kinesis.AmazonKinesisClient
import com.amazonaws.services.kinesis.model._

import org.apache.spark.Logging

/**
 * Shared utility methods for performing Kinesis tests that actually transfer data
 */
private class KinesisTestUtils(
    val endpointUrl: String = "https://kinesis.us-west-2.amazonaws.com",
    _regionName: String = "") extends Logging {

  val regionName = if (_regionName.length == 0) {
    RegionUtils.getRegionByEndpoint(endpointUrl).getName()
  } else {
    RegionUtils.getRegion(_regionName).getName()
  }

  val streamShardCount = 2

  private val createStreamTimeoutSeconds = 300
  private val describeStreamPollTimeSeconds = 1

  @volatile
  private var streamCreated = false

  @volatile
  private var _streamName: String = _

  private lazy val kinesisClient = {
    val client = new AmazonKinesisClient(KinesisTestUtils.getAWSCredentials())
    client.setEndpoint(endpointUrl)
    client
  }

  private lazy val dynamoDB = {
    val dynamoDBClient = new AmazonDynamoDBClient(new DefaultAWSCredentialsProviderChain())
    dynamoDBClient.setRegion(RegionUtils.getRegion(regionName))
    new DynamoDB(dynamoDBClient)
  }

  def streamName: String = {
    require(streamCreated, "Stream not yet created, call createStream() to create one")
    _streamName
  }

  def createStream(): Unit = {
    logInfo("Creating stream")
    require(!streamCreated, "Stream already created")
    _streamName = findNonExistentStreamName()

    // Create a stream. The number of shards determines the provisioned throughput.
    val createStreamRequest = new CreateStreamRequest()
    createStreamRequest.setStreamName(_streamName)
    createStreamRequest.setShardCount(2)
    kinesisClient.createStream(createStreamRequest)

    // The stream is now being created. Wait for it to become active.
    waitForStreamToBeActive(_streamName)
    streamCreated = true
    logInfo("Created stream")
  }

  /**
   * Push data to Kinesis stream and return a map of
   * shardId -> seq of (data, seq number) pushed to corresponding shard
   */
  def pushData(testData: Seq[Int]): Map[String, Seq[(Int, String)]] = {
    require(streamCreated, "Stream not yet created, call createStream() to create one")
    val shardIdToSeqNumbers = new mutable.HashMap[String, ArrayBuffer[(Int, String)]]()

    testData.foreach { num =>
      val str = num.toString
      val putRecordRequest = new PutRecordRequest().withStreamName(streamName)
        .withData(ByteBuffer.wrap(str.getBytes()))
        .withPartitionKey(str)

      val putRecordResult = kinesisClient.putRecord(putRecordRequest)
      val shardId = putRecordResult.getShardId
      val seqNumber = putRecordResult.getSequenceNumber()
      val sentSeqNumbers = shardIdToSeqNumbers.getOrElseUpdate(shardId,
        new ArrayBuffer[(Int, String)]())
      sentSeqNumbers += ((num, seqNumber))
    }

    logInfo(s"Pushed $testData:\n\t ${shardIdToSeqNumbers.mkString("\n\t")}")
    shardIdToSeqNumbers.toMap
  }

  def deleteStream(): Unit = {
    try {
      if (streamCreated) {
        kinesisClient.deleteStream(streamName)
      }
    } catch {
      case e: Exception =>
        logWarning(s"Could not delete stream $streamName")
    }
  }

  def deleteDynamoDBTable(tableName: String): Unit = {
    try {
      val table = dynamoDB.getTable(tableName)
      table.delete()
      table.waitForDelete()
    } catch {
      case e: Exception =>
        logWarning(s"Could not delete DynamoDB table $tableName")
    }
  }

  private def describeStream(streamNameToDescribe: String): Option[StreamDescription] = {
    try {
      val describeStreamRequest = new DescribeStreamRequest().withStreamName(streamNameToDescribe)
      val desc = kinesisClient.describeStream(describeStreamRequest).getStreamDescription()
      Some(desc)
    } catch {
      case rnfe: ResourceNotFoundException =>
        None
    }
  }

  private def findNonExistentStreamName(): String = {
    var testStreamName: String = null
    do {
      Thread.sleep(TimeUnit.SECONDS.toMillis(describeStreamPollTimeSeconds))
      testStreamName = s"KinesisTestUtils-${math.abs(Random.nextLong())}"
    } while (describeStream(testStreamName).nonEmpty)
    testStreamName
  }

  private def waitForStreamToBeActive(streamNameToWaitFor: String): Unit = {
    val startTime = System.currentTimeMillis()
    val endTime = startTime + TimeUnit.SECONDS.toMillis(createStreamTimeoutSeconds)
    while (System.currentTimeMillis() < endTime) {
      Thread.sleep(TimeUnit.SECONDS.toMillis(describeStreamPollTimeSeconds))
      describeStream(streamNameToWaitFor).foreach { description =>
        val streamStatus = description.getStreamStatus()
        logDebug(s"\t- current state: $streamStatus\n")
        if ("ACTIVE".equals(streamStatus)) {
          return
        }
      }
    }
    require(false, s"Stream $streamName never became active")
  }
}

private[kinesis] object KinesisTestUtils {

  val envVarName = "ENABLE_KINESIS_TESTS"

  val shouldRunTests = sys.env.get(envVarName) == Some("1")

  def isAWSCredentialsPresent: Boolean = {
    Try { new DefaultAWSCredentialsProviderChain().getCredentials() }.isSuccess
  }

  def getAWSCredentials(): AWSCredentials = {
    assert(shouldRunTests,
      "Kinesis test not enabled, should not attempt to get AWS credentials")
    Try { new DefaultAWSCredentialsProviderChain().getCredentials() } match {
      case Success(cred) => cred
      case Failure(e) =>
        throw new Exception("Kinesis tests enabled, but could get not AWS credentials")
    }
  }
}
