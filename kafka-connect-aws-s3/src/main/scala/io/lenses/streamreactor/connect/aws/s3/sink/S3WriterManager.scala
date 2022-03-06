
/*
 * Copyright 2020 Lenses.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.lenses.streamreactor.connect.aws.s3.sink


import cats.implicits._
import com.typesafe.scalalogging.{LazyLogging, StrictLogging}
import io.lenses.streamreactor.connect.aws.s3.formats.S3FormatWriter
import io.lenses.streamreactor.connect.aws.s3.model.Offset.orderingByOffsetValue
import io.lenses.streamreactor.connect.aws.s3.model._
import io.lenses.streamreactor.connect.aws.s3.model.location.{RemoteS3PathLocation, RemoteS3RootLocation}
import io.lenses.streamreactor.connect.aws.s3.sink.config.{S3SinkConfig, SinkBucketOptions}
import io.lenses.streamreactor.connect.aws.s3.sink.seek._
import io.lenses.streamreactor.connect.aws.s3.sink.writer.S3Writer
import io.lenses.streamreactor.connect.aws.s3.storage.StorageInterface
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.connect.data.Schema

import java.io.File
import scala.collection.compat.toTraversableLikeExtensionMethods
import scala.collection.{immutable, mutable}

case class MapKey(topicPartition: TopicPartition, partitionValues: immutable.Map[PartitionField, String])

/**
  * Manages the lifecycle of [[S3Writer]] instances.
  *
  * A given sink may be writing to multiple locations (partitions), and therefore
  * it is convenient to extract this to another class.
  *
  * This class is not thread safe as it is not designed to be shared between concurrent
  * sinks, since file handles cannot be safely shared without considerable overhead.
  */
class S3WriterManager(
                       sinkName: String,
                       commitPolicyFn: TopicPartition => Either[SinkError, CommitPolicy],
                       bucketAndPrefixFn: TopicPartition => Either[SinkError, RemoteS3RootLocation],
                       fileNamingStrategyFn: TopicPartition => Either[SinkError, S3FileNamingStrategy],
                       stagingFilenameFn: (TopicPartition, immutable.Map[PartitionField, String]) => Either[SinkError, File],
                       finalFilenameFn: (TopicPartition, immutable.Map[PartitionField, String], Offset) => Either[SinkError, RemoteS3PathLocation],
                       formatWriterFn: (TopicPartition, File) => Either[SinkError, S3FormatWriter],
                       indexManager: IndexManager,
                     )
                     (
                       implicit storageInterface: StorageInterface
                     ) extends StrictLogging {

  private val writers = mutable.Map.empty[MapKey, S3Writer]

  private def writerForTopicPartitionWithMaxOffset(topicPartition: TopicPartition) = {
    writers
      .collect {
        case (key, writer) if key.topicPartition == topicPartition && writer.getCommittedOffset.nonEmpty => writer
      }
      .toList
      .sortBy(_.getCommittedOffset).headOption
  }

  def commitAllWritersIfFlushRequired(): Either[BatchS3SinkError, Unit] = {
    if (writers.values.exists(_.shouldFlush)) {
      commitAllWriters()
    } else {
      ().asRight
    }
  }

  private def commitAllWriters(): Either[BatchS3SinkError, Unit] = {
    logger.debug(s"[{}] Received call to S3WriterManager.commit", sinkName)
    commitWritersWithFilter(_ => true)
  }

  def recommitPending() : Either[SinkError,Unit] = {
    logger.trace(s"[{}] Retry Pending", sinkName)
    val result = commitWritersWithFilter(_._2.hasPendingUpload())
    logger.debug(s"[{}] Retry Pending Complete", sinkName)
    result
  }

  private def commitWriters(topicPartition: TopicPartition): Either[BatchS3SinkError, Unit] = {
    commitWritersWithFilter(_._1.topicPartition == topicPartition)
  }

  private def commitWriters(writer: S3Writer, topicPartition: TopicPartition) = {
    logger.debug(s"[{}] Received call to S3WriterManager.commitWritersWithFilter (w, tp {})", sinkName, topicPartition)
    if (writer.shouldFlush) {
      commitWritersWithFilter(
        (kv: (MapKey, S3Writer)) => kv._1.topicPartition == topicPartition
      )
    } else {
      ().asRight
    }
  }

  private def commitWritersWithFilter(keyValueFilterFn: ((MapKey, S3Writer)) => Boolean): Either[BatchS3SinkError, Unit] = {

    logger.debug(s"[{}] Received call to S3WriterManager.commitWritersWithFilter (filter)", sinkName)
    val writerCommitErrors = writers
      .filter(keyValueFilterFn)
      .mapValues(_.commit)
      .collect {
        case (_, Left(err)) => err
      }.toSet

    if(writerCommitErrors.nonEmpty) {
      BatchS3SinkError(writerCommitErrors).asLeft
    } else {
      ().asRight
    }

  }

  def open(partitions: Set[TopicPartition]): Either[SinkError, Map[TopicPartition, Offset]] = {
    logger.debug(s"[{}] Received call to S3WriterManager.open", sinkName)

    partitions
      .map(seekOffsetsForTopicPartition)
      .partitionMap(identity)
      match {
        case (throwables, _) if throwables.nonEmpty => BatchS3SinkError(throwables).asLeft
        case (_, offsets) =>
          offsets.flatten.map(
            _.toTopicPartitionOffsetTuple
          ).toMap.asRight
    }
  }

  private def seekOffsetsForTopicPartition(topicPartition: TopicPartition): Either[SinkError, Option[TopicPartitionOffset]] = {
    logger.debug(s"[{}] seekOffsetsForTopicPartition {}", sinkName, topicPartition)
      for {
        fileNamingStrategy <- fileNamingStrategyFn(topicPartition)
        bucketAndPrefix <- bucketAndPrefixFn(topicPartition)
        offset <- indexManager.seek(topicPartition, fileNamingStrategy, bucketAndPrefix)
      } yield offset
  }

  def close(): Unit = {
    logger.debug(s"[{}] Received call to S3WriterManager.close", sinkName)
    writers.values.foreach(_.close())
    writers.clear()
  }

  def write(topicPartitionOffset: TopicPartitionOffset, messageDetail: MessageDetail): Either[SinkError, Unit] = {

    logger.debug(s"[$sinkName] Received call to S3WriterManager.write for ${topicPartitionOffset.topic}-${topicPartitionOffset.partition}:${topicPartitionOffset.offset}")
      for {
        writer <- writer(topicPartitionOffset.toTopicPartition, messageDetail)
        shouldSkip = writer.shouldSkip(topicPartitionOffset.offset)
        resultIfNotSkipped <- if (!shouldSkip) {
          writeAndCommit(topicPartitionOffset, messageDetail, writer)
        } else {
          ().asRight
        }
      } yield resultIfNotSkipped
  }

  private def writeAndCommit(topicPartitionOffset: TopicPartitionOffset, messageDetail: MessageDetail, writer: S3Writer): Either[SinkError, Unit] = {
    {
      for {
        // commitException can not be recovered from
        _ <- rollOverTopicPartitionWriters(writer, topicPartitionOffset.toTopicPartition, messageDetail)
        // a processErr can potentially be recovered from in the next iteration.  Can be due to network problems, for
        _ <- writer.write(messageDetail, topicPartitionOffset.offset)
        commitRes <- commitWriters(writer, topicPartitionOffset.toTopicPartition)
      } yield commitRes
    }
  }

  private def rollOverTopicPartitionWriters(
                                             s3Writer: S3Writer,
                                             topicPartition: TopicPartition,
                                             messageDetail: MessageDetail
                                           ): Either[BatchS3SinkError, Unit] = {
    messageDetail.valueSinkData.schema() match {
      case Some(value: Schema) if s3Writer.shouldRollover(value) => commitWriters(topicPartition)
      case _ => ().asRight
    }
  }

  def processPartitionValues(
                              messageDetail: MessageDetail,
                              fileNamingStrategy: S3FileNamingStrategy,
                              topicPartition: TopicPartition
                            ): Either[SinkError, immutable.Map[PartitionField, String]] = {
    if (fileNamingStrategy.shouldProcessPartitionValues) {
      fileNamingStrategy.processPartitionValues(messageDetail, topicPartition)
    } else {
      immutable.Map.empty[PartitionField, String].asRight
    }
  }

  /**
    * Returns a writer that can write records for a particular topic and partition.
    * The writer will create a file inside the given directory if there is no open writer.
    */
  private def writer(topicPartition: TopicPartition, messageDetail: MessageDetail): Either[SinkError, S3Writer] = {
    for {
      bucketAndPrefix <- bucketAndPrefixFn(topicPartition)
      fileNamingStrategy <- fileNamingStrategyFn(topicPartition)
      partitionValues <- processPartitionValues(messageDetail, fileNamingStrategy, topicPartition)
    } yield writers.getOrElseUpdate(
      MapKey(topicPartition, partitionValues), createWriter(bucketAndPrefix, topicPartition, partitionValues) match {
        case Left(ex) => return ex.asLeft[S3Writer]
        case Right(value) => value
      }
    )
  }

  private def createWriter(bucketAndPrefix: RemoteS3RootLocation, topicPartition: TopicPartition, partitionValues: immutable.Map[PartitionField, String]): Either[SinkError, S3Writer] = {
    logger.debug(s"[$sinkName] Creating new writer for bucketAndPrefix:$bucketAndPrefix")
    for {
      commitPolicy <- commitPolicyFn(topicPartition)
    } yield {
      new S3Writer(
        sinkName,
        topicPartition,
        commitPolicy,
        indexManager,
        () => stagingFilenameFn(topicPartition, partitionValues),
        finalFilenameFn.curried(topicPartition)(partitionValues),
        formatWriterFn.curried(topicPartition),
      )
    }
  }

  def preCommit(currentOffsets: immutable.Map[TopicPartition, OffsetAndMetadata]): immutable.Map[TopicPartition, OffsetAndMetadata] = {
    currentOffsets.map {
      case (tp, offsetAndMetadata) =>
        tp -> writerForTopicPartitionWithMaxOffset(tp)
          .map(createOffsetAndMetadata(offsetAndMetadata, _))
          .getOrElse(offsetAndMetadata)
    }
  }

  private def createOffsetAndMetadata(offsetAndMetadata: OffsetAndMetadata, writer: S3Writer) = {
    new OffsetAndMetadata(
      writer.getCommittedOffset.get.value,
      offsetAndMetadata.leaderEpoch(),
      offsetAndMetadata.metadata()
    )
  }

  def cleanUp(topicPartition: TopicPartition): Unit = {
    writers
      .filterKeys(mapKey => mapKey
        .topicPartition == topicPartition)
      .keys
      .foreach(writers.remove)
  }

}

object S3WriterManager extends LazyLogging {

  def from(config: S3SinkConfig, sinkName: String)
          (implicit storageInterface: StorageInterface): S3WriterManager = {

    val bucketAndPrefixFn: TopicPartition => Either[SinkError, RemoteS3RootLocation] = topicPartition => {
      bucketOptsForTopic(config, topicPartition.topic) match {
        case Some(sBO) => sBO.bucketAndPrefix.asRight
        case None => FatalS3SinkError.apply(s"No bucket config for ${topicPartition.topic}", topicPartition).asLeft
      }
    }

    val commitPolicyFn: TopicPartition => Either[SinkError, CommitPolicy] = topicPartition => bucketOptsForTopic(config, topicPartition.topic) match {
      case Some(bucketOptions) => bucketOptions.commitPolicy.asRight
      case None => FatalS3SinkError("Can't find commitPolicy in config", topicPartition).asLeft
    }

    val fileNamingStrategyFn: TopicPartition => Either[SinkError, S3FileNamingStrategy] = topicPartition =>
      bucketOptsForTopic(config, topicPartition.topic) match {
        case Some(bucketOptions) => bucketOptions.fileNamingStrategy.asRight
        case None => FatalS3SinkError("Can't find fileNamingStrategy in config", topicPartition).asLeft
      }

    val stagingFilenameFn: (TopicPartition, immutable.Map[PartitionField, String]) => Either[SinkError, File] = (topicPartition, partitionValues) =>
      bucketOptsForTopic(config, topicPartition.topic) match {
        case Some(bucketOptions) =>
          for {
            fileNamingStrategy <- fileNamingStrategyFn(topicPartition)
            stagingFilename <- fileNamingStrategy.stagingFile(bucketOptions.localStagingArea.dir, bucketOptions.bucketAndPrefix, topicPartition, partitionValues)
          } yield stagingFilename
        case None => FatalS3SinkError("Can't find fileNamingStrategy in config", topicPartition).asLeft
      }

    val finalFilenameFn: (TopicPartition, immutable.Map[PartitionField, String], Offset) => Either[SinkError, RemoteS3PathLocation] = (topicPartition, partitionValues, offset) =>
      bucketOptsForTopic(config, topicPartition.topic) match {
        case Some(bucketOptions) =>
          for {
            fileNamingStrategy <- fileNamingStrategyFn(topicPartition)
            stagingFilename <- fileNamingStrategy.finalFilename(bucketOptions.bucketAndPrefix, topicPartition.withOffset(offset), partitionValues)
          } yield stagingFilename
        case None => FatalS3SinkError("Can't find fileNamingStrategy in config", topicPartition).asLeft
      }

    val formatWriterFn: (TopicPartition, File) => Either[SinkError, S3FormatWriter] = (topicPartition: TopicPartition, stagingFilename) =>
      bucketOptsForTopic(config, topicPartition.topic) match {
        case Some(bucketOptions) =>
          for {
            formatWriter <- S3FormatWriter(
              bucketOptions.formatSelection,
              stagingFilename,
              topicPartition
              )
          } yield formatWriter
        case None => FatalS3SinkError("Can't find format choice in config", topicPartition).asLeft
      }

    // TODO: use Option.when when we get to 2.13
    val maybeLegacyOffsetSeeker = if(config.offsetSeekerOptions.migrate) Some(new LegacyOffsetSeeker(sinkName)) else None
    val indexManager = new IndexManager(sinkName, config.offsetSeekerOptions.maxIndexFiles, maybeLegacyOffsetSeeker)

    new S3WriterManager(
      sinkName,
      commitPolicyFn,
      bucketAndPrefixFn,
      fileNamingStrategyFn,
      stagingFilenameFn,
      finalFilenameFn,
      formatWriterFn,
      indexManager
    )
  }

  private def bucketOptsForTopic(config: S3SinkConfig, topic: Topic): Option[SinkBucketOptions] = {
    config.bucketOptions.find(_.sourceTopic == topic.value)
  }

}
